"""Celery tasks for the grading pipeline."""

from concurrent.futures import ThreadPoolExecutor, as_completed

import json

from decimal import Decimal

import uuid



from minio import Minio

import redis as redis_lib
from sqlalchemy import text



from celery_app import app

from config import (

    DIMENSION_SCORE_CONCURRENCY,

    MINIO_ACCESS_KEY,

    MINIO_BUCKET,

    MINIO_ENDPOINT,

    MINIO_SECURE,

    MINIO_SECRET_KEY,

    OCR_STRATEGY,

    REDIS_HOST,

    REDIS_PORT,

    REDIS_USERNAME,

    REDIS_PASSWORD,

    RESULT_CHANNEL,

    USE_VLM_UNIFIED_ANALYSIS,
    COVER_RECOGNITION_ENABLED,
    CODE_ANALYSIS_ENABLED,
    IMPROVEMENT_PLAN_ENABLED,

)

from models.db_models import (

    EvidenceBlock as EvidenceBlockDB,

    GradingRubric,

    GradingSubmission,

    ReportFile,

    ScoreItem,

    get_session,

)

from models.pipeline_models import EvidenceBlock, ImageKind, TaskMessage

from pipeline.document_parser import parse_document

from pipeline.evidence_builder import build_evidence_packs

from pipeline.code_analyzer import analyze_code

from pipeline.improvement_planner import build_plan

from pipeline.image_classifier import classify_image

from pipeline.ocr_processor import run_ocr

from pipeline.score_calculator import calculate_weighted_total

from pipeline.scorer import score_dimension, score_dimensions_batch

from pipeline.trace_logger import trace_step

from pipeline.vlm_client import call_vlm
from pipeline.cover_parser import recognize_cover_objectives
from pipeline.progress_reporter import publish_progress



DEFAULT_SCORE_RANGE_MIN = 75.0

DEFAULT_SCORE_RANGE_MAX = 99.0



# 当一页的文字长度达到此阈值时，认为该页已有充足文字证据，

# 图片提取失败不再生成 vlm_failed 证据块。

PAGE_TEXT_SUBSTANTIAL_THRESHOLD = 100





def _get_minio():

    return Minio(

        MINIO_ENDPOINT,

        access_key=MINIO_ACCESS_KEY,

        secret_key=MINIO_SECRET_KEY,

        secure=MINIO_SECURE,

    )





def _get_redis():

    return redis_lib.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0, decode_responses=True, username=REDIS_USERNAME or None, password=REDIS_PASSWORD or None)





def _upload_image(minio_client, submission_id: int, ev_counter: int, image_bytes: bytes):

    """Upload evidence image to MinIO and return object key."""

    img_key = f"grading/{submission_id}/img-{ev_counter}.png"

    try:

        import io



        minio_client.put_object(

            MINIO_BUCKET,

            img_key,

            io.BytesIO(image_bytes),

            len(image_bytes),

            content_type="image/png",

        )

        return img_key

    except Exception:

        return None





def _next_evidence_id(submission_id: int, run_tag: str, ev_counter: int) -> str:

    """Generate per-run evidence ids so retries do not clash with stale rows."""

    return f"ev-{submission_id}-{run_tag}-{ev_counter:04d}"





def _reset_submission_artifacts(session, submission_id: int):

    """Clear stale grading rows before retrying or rescoring a submission."""

    session.query(ReportFile).filter(ReportFile.submission_id == submission_id).delete(synchronize_session=False)

    session.query(ScoreItem).filter(ScoreItem.submission_id == submission_id).delete(synchronize_session=False)

    session.query(EvidenceBlockDB).filter(EvidenceBlockDB.submission_id == submission_id).delete(synchronize_session=False)

    session.commit()





def _is_useful_ocr(text: str, confidence: float) -> bool:

    stripped = (text or "").strip()

    return len(stripped) >= 40 or (len(stripped) >= 20 and float(confidence or 0.0) >= 0.68)





def _extract_vlm_text(image_bytes: bytes):

    result = call_vlm(image_bytes, task="extract_text")

    payload = result.description_json or {}

    recognized = str(payload.get("recognized_text") or "").strip()

    summary = str(payload.get("summary") or "").strip()

    raw = str(payload.get("raw") or "").strip()

    if not recognized and raw:

        recognized = raw

    if not summary and raw:

        summary = raw

    try:

        confidence = float(payload.get("confidence") or 0.0)

    except Exception:

        confidence = 0.0

    text = recognized if len(recognized) >= len(summary) else summary

    useful = ("error" not in payload) and (len(text) >= 20 or confidence >= 0.55)



    # Retry once with a more descriptive VLM prompt if text extraction output is sparse.

    if not useful:

        describe = call_vlm(image_bytes, task="describe")

        describe_payload = describe.description_json or {}

        recognized2 = str(describe_payload.get("recognized_text") or "").strip()

        summary2 = str(describe_payload.get("summary") or "").strip()

        raw2 = str(describe_payload.get("raw") or "").strip()

        if not recognized2 and raw2:

            recognized2 = raw2

        if not summary2 and raw2:

            summary2 = raw2

        try:

            confidence2 = float(describe_payload.get("confidence") or 0.0)

        except Exception:

            confidence2 = 0.0

        text2 = recognized2 if len(recognized2) >= len(summary2) else summary2

        if ("error" not in describe_payload) and (len(text2) >= 20 or confidence2 >= 0.55):

            payload = {"extract_text": payload, "describe_fallback": describe_payload}

            text = text2

            confidence = max(confidence, confidence2)

            useful = True



    return useful, text, confidence, payload





def _vlm_describe_image(submission_id: int, image_bytes: bytes):

    with trace_step(submission_id, "vlm") as info:

        result = call_vlm(image_bytes, task="describe")

    payload = result.description_json or {}

    useful = payload and "error" not in payload and "VLM not configured" not in str(payload)

    return useful, payload





def _analyze_image_with_vlm(image_bytes: bytes):

    """Unified VLM analysis: classify + extract/describe in one call.



    Returns (result_dict, payload) where result_dict may be None on failure.

    result_dict contains: image_type, recognized_text, summary, text, confidence, useful, is_text_heavy.

    """

    result = call_vlm(image_bytes, task="analyze")

    payload = result.description_json or {}

    if not payload or "error" in payload:

        return None, payload



    image_type = str(payload.get("image_type") or "other").lower().strip()

    recognized_text = str(payload.get("recognized_text") or "").strip()

    summary = str(payload.get("summary") or "").strip()

    raw = str(payload.get("raw") or "").strip()



    if not recognized_text and raw:

        recognized_text = raw

    if not summary and raw:

        summary = raw



    try:

        confidence = float(payload.get("confidence") or 0.0)

    except Exception:

        confidence = 0.0



    is_text_heavy = image_type in ("code_screenshot", "terminal_log")

    if is_text_heavy:

        useful = len(recognized_text) >= 20 or confidence >= 0.55

        text = recognized_text

    else:

        useful = len(summary) >= 20 or len(recognized_text) >= 20 or confidence >= 0.55

        text = summary if len(summary) >= len(recognized_text) else recognized_text



    return {

        "image_type": image_type,

        "recognized_text": recognized_text,

        "summary": summary,

        "text": text,

        "confidence": confidence,

        "useful": useful,

        "is_text_heavy": is_text_heavy,

    }, payload





def _should_try_ocr_first() -> bool:

    return OCR_STRATEGY == "ocr_first"





def _should_allow_ocr_fallback() -> bool:

    return OCR_STRATEGY in ("ocr_first", "qwen_first")





def _run_ocr_if_needed(submission_id: int, image_bytes: bytes):

    if not _should_allow_ocr_fallback():

        return "", 0.0

    return _run_ocr_force(submission_id, image_bytes)





def _run_ocr_force(submission_id: int, image_bytes: bytes):

    """Run OCR unconditionally, bypassing OCR_STRATEGY checks."""

    with trace_step(submission_id, "ocr") as info:

        ocr_result = run_ocr(image_bytes)

    return ocr_result.text.strip(), ocr_result.confidence





def _append_image_failure(evidence_blocks, minio_client, submission_id, run_tag, ev_counter, page_num, img, kind, confidence, payload=None):

    img_key = _upload_image(minio_client, submission_id, ev_counter, img.image_bytes)

    metadata = {"image_kind": str(kind), "ocr_empty": True}

    if payload:

        metadata["vlm_payload"] = payload

    evidence_blocks.append(EvidenceBlock(

        evidence_id=_next_evidence_id(submission_id, run_tag, ev_counter),

        kind="vlm_failed",

        page=page_num,

        content="Image evidence exists, but the multimodal model did not extract usable content.",

        confidence=confidence,

        image_key=img_key,

        bbox=img.bbox,

        metadata=metadata,

    ))





@app.task(bind=True, max_retries=3, default_retry_delay=30)

def process_submission(self, task_message_json: str):

    """Main pipeline task: process a single student submission."""

    msg = TaskMessage(**json.loads(task_message_json))

    session = get_session()

    r = _get_redis()



    try:

        sub = session.query(GradingSubmission).get(msg.submissionId)

        if not sub:

            return



        _reset_submission_artifacts(session, msg.submissionId)
        publish_progress(r, msg.taskId, msg.submissionId, "parsing", student_name=sub.student_name)

        sub.status = "PROCESSING"

        sub.total_score = None

        sub.error_message = None

        session.commit()



        minio_client = _get_minio()

        with trace_step(msg.submissionId, "document_download") as info:

            response = minio_client.get_object(MINIO_BUCKET, msg.pdfObjectKey)

            source_bytes = response.read()

            response.close()

            response.release_conn()



        with trace_step(msg.submissionId, "document_parse") as info:

            parsed = parse_document(source_bytes, msg.originalFilename)

            if parsed.error:

                _fail_submission(session, sub, parsed.error, r, msg.submissionId)

                return



        # VLM 首页识别：识别学生报告封面的"课程目标"表（目标编号、各目标满分、评价区间），
        # 供后端把评分标准维度可靠对位到封面目标行，并按真实满分回填。
        if COVER_RECOGNITION_ENABLED:
            publish_progress(r, msg.taskId, msg.submissionId, "cover_recognize", student_name=sub.student_name)
            try:
                with trace_step(msg.submissionId, "cover_recognize") as info:
                    cover = recognize_cover_objectives(source_bytes)
                if cover:
                    # 用原生 UPDATE 写入，避免把该列映射进 ORM 后导致每次 SELECT 都依赖该列
                    # （在迁移尚未应用时可平滑降级）。
                    session.execute(
                        text("UPDATE grading_submission SET cover_objectives_json = :v WHERE id = :id"),
                        {"v": json.dumps(cover, ensure_ascii=False), "id": msg.submissionId},
                    )
                    session.commit()
            except Exception:
                session.rollback()

        publish_progress(r, msg.taskId, msg.submissionId, "evidence", student_name=sub.student_name)

        evidence_blocks: list[EvidenceBlock] = []

        ev_counter = 0

        failure_pages = set()

        run_tag = uuid.uuid4().hex[:8]



        for page in parsed.pages:

            page_text_stripped = page.text.strip()

            has_page_text = bool(page_text_stripped)

            has_substantial_page_text = len(page_text_stripped) >= PAGE_TEXT_SUBSTANTIAL_THRESHOLD

            if page_text_stripped:

                ev_counter += 1

                # For DOCX, the virtual page already groups paragraphs; store page-level

                # location with a paragraphIndex hint of 0 (the start of this virtual page).

                evidence_blocks.append(EvidenceBlock(

                    evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                    kind="text",

                    page=page.page_num,

                    content=page.text[:2000],

                    location={"page": page.page_num, "paragraphIndex": 0},

                ))



            for img in page.images:

                with trace_step(msg.submissionId, "image_classify") as info:

                    kind = classify_image(img.image_bytes)

                    img.kind = kind



                if img.bbox and len(img.bbox) == 4:

                    w = abs(img.bbox[2] - img.bbox[0])

                    h = abs(img.bbox[3] - img.bbox[1])

                    if w < 20 or h < 20:

                        continue



                # Unified VLM analysis path: one call does classification + extraction/description.

                if USE_VLM_UNIFIED_ANALYSIS:

                    with trace_step(msg.submissionId, "vlm_analyze") as info:

                        analysis, vlm_payload = _analyze_image_with_vlm(img.image_bytes)

                    if analysis and analysis["useful"]:

                        ev_counter += 1

                        img_key = _upload_image(minio_client, msg.submissionId, ev_counter, img.image_bytes)

                        evidence_blocks.append(EvidenceBlock(

                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                            kind="vlm",

                            page=page.page_num,

                            content=analysis["text"],

                            confidence=max(analysis["confidence"], 0.0),

                            image_key=img_key,

                            bbox=img.bbox,

                            location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                            metadata={

                                "image_kind": analysis["image_type"],

                                "vlm_payload": vlm_payload,

                                "recognized_text": analysis["recognized_text"],

                                "summary": analysis["summary"],

                            },

                        ))

                        continue



                if kind in (ImageKind.DIAGRAM, ImageKind.PLOT):

                    vlm_useful, vlm_payload = _vlm_describe_image(msg.submissionId, img.image_bytes)

                    if vlm_useful:

                        ev_counter += 1

                        evidence_blocks.append(EvidenceBlock(

                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                            kind="vlm",

                            page=page.page_num,

                            content=json.dumps(vlm_payload, ensure_ascii=False),

                            bbox=img.bbox,

                            location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                        ))

                        continue



                    ocr_text, ocr_conf = _run_ocr_force(msg.submissionId, img.image_bytes)

                    if _is_useful_ocr(ocr_text, ocr_conf):

                        ev_counter += 1

                        img_key = _upload_image(minio_client, msg.submissionId, ev_counter, img.image_bytes)

                        evidence_blocks.append(EvidenceBlock(

                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                            kind="ocr",

                            page=page.page_num,

                            content=ocr_text,

                            confidence=ocr_conf,

                            image_key=img_key,

                            bbox=img.bbox,

                            location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                        ))

                        continue



                    ev_counter += 1

                    if not has_substantial_page_text and page.page_num not in failure_pages:

                        _append_image_failure(

                            evidence_blocks,

                            minio_client,

                            msg.submissionId,

                            run_tag,

                            ev_counter,

                            page.page_num,

                            img,

                            kind,

                            ocr_conf,

                            vlm_payload,

                        )

                        failure_pages.add(page.page_num)

                    continue



                ocr_text = ""

                ocr_conf = 0.0

                vlm_useful = False

                vlm_text = ""

                vlm_conf = 0.0

                vlm_payload = {}



                if _should_try_ocr_first():

                    ocr_text, ocr_conf = _run_ocr_if_needed(msg.submissionId, img.image_bytes)

                    if _is_useful_ocr(ocr_text, ocr_conf):

                        ev_counter += 1

                        img_key = _upload_image(minio_client, msg.submissionId, ev_counter, img.image_bytes)

                        evidence_blocks.append(EvidenceBlock(

                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                            kind="ocr",

                            page=page.page_num,

                            content=ocr_text,

                            confidence=ocr_conf,

                            image_key=img_key,

                            bbox=img.bbox,

                            location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                        ))

                        continue



                    with trace_step(msg.submissionId, "vlm_fallback") as info:

                        vlm_useful, vlm_text, vlm_conf, vlm_payload = _extract_vlm_text(img.image_bytes)

                else:

                    with trace_step(msg.submissionId, "vlm_primary") as info:

                        vlm_useful, vlm_text, vlm_conf, vlm_payload = _extract_vlm_text(img.image_bytes)



                    if not vlm_useful:

                        ocr_text, ocr_conf = _run_ocr_force(msg.submissionId, img.image_bytes)

                        if _is_useful_ocr(ocr_text, ocr_conf):

                            ev_counter += 1

                            img_key = _upload_image(minio_client, msg.submissionId, ev_counter, img.image_bytes)

                            evidence_blocks.append(EvidenceBlock(

                                evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                                kind="ocr",

                                page=page.page_num,

                                content=ocr_text,

                                confidence=ocr_conf,

                                image_key=img_key,

                                bbox=img.bbox,

                                location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                            ))

                            continue



                ev_counter += 1

                img_key = _upload_image(minio_client, msg.submissionId, ev_counter, img.image_bytes)

                if vlm_useful:

                    evidence_blocks.append(EvidenceBlock(

                        evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                        kind="vlm",

                        page=page.page_num,

                        content=vlm_text,

                        confidence=max(ocr_conf, vlm_conf),

                        image_key=img_key,

                        bbox=img.bbox,

                        location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                        metadata={"image_kind": str(kind), "vlm_payload": vlm_payload},

                    ))

                else:

                    if not has_substantial_page_text and page.page_num not in failure_pages:

                        evidence_blocks.append(EvidenceBlock(

                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),

                            kind="vlm_failed",

                            page=page.page_num,

                            content="Image evidence exists, but the multimodal model did not extract usable content.",

                            confidence=max(ocr_conf, vlm_conf),

                            image_key=img_key,

                            bbox=img.bbox,

                            location={"page": page.page_num, "paragraphIndex": getattr(img, "paragraph_index", None)},

                            metadata={"image_kind": str(kind), "ocr_empty": True, "vlm_payload": vlm_payload},

                        ))

                        failure_pages.add(page.page_num)



        with trace_step(msg.submissionId, "save_evidence") as info:

            for eb in evidence_blocks:

                location = eb.location

                if location is None and eb.kind in ("vlm", "ocr", "vlm_failed", "image"):

                    location = {"page": eb.page}

                    if eb.bbox:

                        location["bbox"] = eb.bbox

                db_eb = EvidenceBlockDB(

                    submission_id=msg.submissionId,

                    evidence_id=eb.evidence_id,

                    kind=eb.kind,

                    page=eb.page,

                bbox_json=eb.bbox if eb.bbox else None,

                    content=eb.content,

                    confidence=eb.confidence,

                    image_key=eb.image_key,

                metadata_json=eb.metadata if eb.metadata else None,

                location_json=location if location else None,

                )

                session.add(db_eb)

            session.commit()



        rubric = session.query(GradingRubric).get(msg.rubricId)

        if not rubric:

            _fail_submission(session, sub, "Rubric not found", r, msg.submissionId)

            return



        dimensions = []

        for dim in rubric.dimensions:

            dimensions.append({

                "id": dim.id,

                "name": dim.name,

                "description": dim.description or "",

                "max_score": float(dim.max_score),

                "weight": int(dim.weight),

            })



        code_analysis_result = None
        code_findings = []
        if CODE_ANALYSIS_ENABLED:
            publish_progress(r, msg.taskId, msg.submissionId, "code_analysis", student_name=sub.student_name)
            try:
                with trace_step(msg.submissionId, "code_analysis") as info:
                    code_analysis_result = analyze_code(
                        evidence_blocks, dimensions, getattr(rubric, "subject", "") or ""
                    )
                if code_analysis_result:
                    code_findings = code_analysis_result.get("findings", []) or []
                    summary_text = (code_analysis_result.get("code_summary") or "").strip()
                    if summary_text:
                        # 把代码正确性概述作为一条证据回喂 scorer,让代码/实现类维度参考逻辑正确性。
                        ev_counter += 1
                        evidence_blocks.append(EvidenceBlock(
                            evidence_id=_next_evidence_id(msg.submissionId, run_tag, ev_counter),
                            kind="code_analysis",
                            page=0,
                            content=f"[代码逻辑分析] {summary_text}",
                            metadata={"synthetic": True, "source": "code_analyzer"},
                        ))
            except Exception:
                code_analysis_result = None
                code_findings = []

        with trace_step(msg.submissionId, "evidence_build") as info:

            packs = build_evidence_packs(evidence_blocks, dimensions)



        score_guidance = msg.customPrompt

        effective_score_range_min = msg.scoreRangeMin if msg.scoreRangeMin is not None else DEFAULT_SCORE_RANGE_MIN

        effective_score_range_max = msg.scoreRangeMax if msg.scoreRangeMax is not None else DEFAULT_SCORE_RANGE_MAX

        range_hint = (

            f"Overall score calibration hint: the teacher expects most submissions in this batch "

            f"to fall around {effective_score_range_min:.0f}-{effective_score_range_max:.0f} / 100. "

            f"Use this only as a reference and do not force every dimension to match it. "

            f"Focus on knowledge mastery, experiment reasoning, result analysis, and problem solving. "

            f"If the report contains sections such as 实验目的, 上机要求, 实验要求, or 实验内容, treat them as the concrete experiment targets and assess whether the student actually completed them. "

            f"Do not mainly deduct points for formatting, experiment environment, or Python version unless the rubric explicitly requires them."

        )

        score_guidance = f"{score_guidance}\n\n{range_hint}" if score_guidance else range_hint



        def _score_one_dim(dim):

            dim_id = dim["id"]

            pack = packs.get(dim_id)

            if not pack or not pack.blocks:

                return dim_id, {

                    "score": None,

                    "max_score": dim["max_score"],

                    "weight": dim["weight"],

                    "status": "NEED_MORE_EVIDENCE",

                    "comment": "No usable evidence was extracted for this dimension.",

                    "evidence_ids": [],

                }



            with trace_step(msg.submissionId, f"score_dim_{dim_id}") as info:

                sr, trace_info = score_dimension(

                    pack, dim, custom_prompt=score_guidance,

                    score_range_min=msg.scoreRangeMin,

                    score_range_max=msg.scoreRangeMax,

                )

                info["model_used"] = trace_info.get("model_used")

                info["input_tokens"] = trace_info.get("input_tokens")

                info["output_tokens"] = trace_info.get("output_tokens")

            return dim_id, {

                "score": sr.score,

                "max_score": sr.max_score,

                "weight": dim["weight"],

                "status": sr.status,

                "comment": sr.comment,

                "evidence_ids": sr.evidence_ids,

                "annotations": sr.annotations,

            }



        publish_progress(r, msg.taskId, msg.submissionId, "scoring", student_name=sub.student_name)

        score_by_dim = {}

        try:

            with trace_step(msg.submissionId, "score_batch") as info:

                batch_results, trace_info = score_dimensions_batch(

                    packs,

                    dimensions,

                    custom_prompt=msg.customPrompt,

                    score_range_min=msg.scoreRangeMin,

                    score_range_max=msg.scoreRangeMax,

                )

                info["model_used"] = trace_info.get("model_used")

                info["input_tokens"] = trace_info.get("input_tokens")

                info["output_tokens"] = trace_info.get("output_tokens")

                info["mode"] = trace_info.get("mode")



            for dim in dimensions:

                sr = batch_results[int(dim["id"])]

                score_by_dim[int(dim["id"])] = {

                    "score": sr.score,

                    "max_score": sr.max_score,

                    "weight": dim["weight"],

                    "status": sr.status,

                    "comment": sr.comment,

                    "evidence_ids": sr.evidence_ids,

                    "annotations": sr.annotations,

                }

        except Exception:

            score_workers = max(1, min(DIMENSION_SCORE_CONCURRENCY, len(dimensions)))

            if score_workers == 1:

                for dim in dimensions:

                    dim_id, sr_data = _score_one_dim(dim)

                    score_by_dim[dim_id] = sr_data

            else:

                with ThreadPoolExecutor(max_workers=score_workers) as pool:

                    futures = [pool.submit(_score_one_dim, dim) for dim in dimensions]

                    for future in as_completed(futures):

                        dim_id, sr_data = future.result()

                        score_by_dim[dim_id] = sr_data



        score_dicts = []

        for dim in dimensions:

            dim_id = dim["id"]

            sr_data = score_by_dim.get(dim_id, {

                "score": None,

                "max_score": dim["max_score"],

                "weight": dim["weight"],

                "status": "NEED_MORE_EVIDENCE",

                "comment": "Scoring did not return a result for this dimension.",

                "evidence_ids": [],

            })



            db_si = ScoreItem(

                submission_id=msg.submissionId,

                dimension_id=dim_id,

                score=sr_data["score"],

                max_score=sr_data["max_score"],

                weight=sr_data["weight"],

                comment=sr_data["comment"],

                evidence_ids_json=sr_data["evidence_ids"],

                annotations_json=sr_data.get("annotations", []),

                status=sr_data["status"],

            )

            session.add(db_si)

            score_dicts.append(sr_data)



        session.commit()



        total = calculate_weighted_total(score_dicts)

        need_more_count = sum(1 for s in score_dicts if s["status"] == "NEED_MORE_EVIDENCE")



        sub.total_score = Decimal(str(total))

        sub.status = "NEED_MORE_EVIDENCE" if need_more_count == len(score_dicts) else "SCORED"

        session.commit()



        improvement_plan = None
        if IMPROVEMENT_PLAN_ENABLED:
            publish_progress(r, msg.taskId, msg.submissionId, "planning", student_name=sub.student_name)
            try:
                with trace_step(msg.submissionId, "improvement_plan") as info:
                    improvement_plan = build_plan(score_dicts, dimensions, code_findings, float(total))
            except Exception:
                improvement_plan = None

        # 用原生 UPDATE 写入两个旁路增量结果列(同 cover_objectives_json 的降级策略:
        # 迁移未应用时不因 ORM 映射导致 SELECT 依赖新列)。
        try:
            if code_analysis_result is not None or improvement_plan is not None:
                session.execute(
                    text(
                        "UPDATE grading_submission SET code_analysis_json = :ca, "
                        "improvement_plan_json = :ip WHERE id = :id"
                    ),
                    {
                        "ca": json.dumps(code_analysis_result, ensure_ascii=False)
                        if code_analysis_result is not None else None,
                        "ip": json.dumps(improvement_plan, ensure_ascii=False)
                        if improvement_plan is not None else None,
                        "id": msg.submissionId,
                    },
                )
                session.commit()
        except Exception:
            session.rollback()



        publish_progress(r, msg.taskId, msg.submissionId, "report", student_name=sub.student_name)

        with trace_step(msg.submissionId, "report_generate") as info:

            try:

                from pipeline.report_builder import generate_pdf



                report_scores = []

                for dim, sd in zip(dimensions, score_dicts):

                    report_scores.append({

                        "dimension_name": dim["name"],

                        "score": sd["score"],

                        "max_score": sd["max_score"],

                        "weight": sd["weight"],

                        "comment": sd["comment"],

                        "status": sd["status"],

                        "evidence_ids": sd["evidence_ids"],

                    })



                report_evidence = [

                    {

                        "evidence_id": eb.evidence_id,

                        "kind": eb.kind,

                        "page": eb.page,

                        "content": eb.content,

                    }

                    for eb in evidence_blocks

                    if eb.kind != "code_analysis"

                ]



                pdf_bytes = generate_pdf(

                    sub.student_name or "unknown",

                    report_scores,

                    report_evidence,

                    float(total),

                    code_analysis=code_analysis_result,

                    improvement_plan=improvement_plan,

                )



                report_key = f"grading/{msg.submissionId}/report.pdf"

                import io as _io



                minio_client.put_object(

                    MINIO_BUCKET,

                    report_key,

                    _io.BytesIO(pdf_bytes),

                    len(pdf_bytes),

                    content_type="application/pdf",

                )



                report_file = ReportFile(

                    task_id=msg.taskId,

                    submission_id=msg.submissionId,

                    file_type="pdf",

                    object_key=report_key,

                )

                session.add(report_file)

                session.commit()

            except Exception as report_err:

                info["status"] = "FAILED"

                info["error_message"] = str(report_err)[:500]



        _notify_result(r, msg.submissionId, sub.status, total)
        publish_progress(r, msg.taskId, msg.submissionId, "done", status="COMPLETED",
                         student_name=sub.student_name, percent=100)



    except Exception as exc:

        session.rollback()

        try:

            sub = session.query(GradingSubmission).get(msg.submissionId)

            if sub:

                _fail_submission(session, sub, str(exc)[:500], r, msg.submissionId)

        except Exception:

            pass

        raise self.retry(exc=exc)

    finally:

        session.close()





def _fail_submission(session, sub, error_msg, redis_client, submission_id):

    sub.status = "FAILED"

    sub.error_message = error_msg

    session.commit()

    try:
        publish_progress(redis_client, getattr(sub, "task_id", None), submission_id, "done",
                         status="FAILED", student_name=getattr(sub, "student_name", None))
    except Exception:
        pass
    _notify_result(redis_client, submission_id, "FAILED", None)





def _notify_result(redis_client, submission_id, status, total_score):

    msg = json.dumps({

        "submissionId": submission_id,

        "status": status,

        "totalScore": str(total_score) if total_score is not None else None,

    })

    try:

        redis_client.publish(RESULT_CHANNEL, msg)

    except Exception:

        pass





@app.task(bind=True, max_retries=2, default_retry_delay=60)

def process_rag_document(self, task_message_json: str):

    """RAG document processing task."""

    try:

        msg = json.loads(task_message_json)

        course_space_doc_id = msg["courseSpaceDocId"]

        from pipeline.rag.rag_processor import process_document



        process_document(course_space_doc_id)

    except Exception as exc:

        raise self.retry(exc=exc)

