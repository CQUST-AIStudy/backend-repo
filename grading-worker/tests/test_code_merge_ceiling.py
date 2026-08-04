from models.pipeline_models import EvidenceBlock
from pipeline.code_block_extractor import merge_code_evidence_blocks
from pipeline.scorer import code_error_ceiling


def _code(eid, page, content):
    return EvidenceBlock(evidence_id=eid, kind="code", page=page, content=content)


def test_merge_code_evidence_blocks_joins_split_function_across_pages():
    blocks = [
        EvidenceBlock(evidence_id="t1", kind="text", page=1, content="正文"),
        _code("c1", 1, "int main() {\n    int a[3];\n    for (int i = 0; i <= 3; i++) {"),
        _code("c2", 2, "        a[i] = i;\n    }\n    return 0;\n}"),
        _code("c3", 4, "void other() {\n    int x = 1;\n}"),
    ]

    merged = merge_code_evidence_blocks(blocks)

    code_blocks = [b for b in merged if b.kind == "code"]
    assert len(code_blocks) == 2
    assert "int main()" in code_blocks[0].content
    assert "return 0;" in code_blocks[0].content
    assert code_blocks[0].metadata.get("merged_pages") is True
    # 独立完整代码块不被合并
    assert code_blocks[1].content.startswith("void other()")
    # 非 code 块保留
    assert any(b.kind == "text" for b in merged)


def test_merge_does_not_join_balanced_blocks():
    blocks = [
        _code("c1", 1, "int f() {\n    return 1;\n}"),
        _code("c2", 2, "int g() {\n    return 2;\n}"),
    ]
    merged = merge_code_evidence_blocks(blocks)
    assert len([b for b in merged if b.kind == "code"]) == 2


def test_code_error_ceiling_penalizes_unfixed_errors():
    # 无错误时上限即教师区间上限
    assert code_error_ceiling(0, 0, 0.99) == 0.99
    # 2 处 HIGH + 1 处 MEDIUM => 扣 12%
    assert round(code_error_ceiling(2, 1, 0.99), 2) == 0.87
    # 扣分封顶 25%，下限 55%
    assert code_error_ceiling(10, 10, 0.99) == 0.74
    assert code_error_ceiling(100, 100, 0.6) == 0.55
