# 代码块提取 Spec：Worker 阶段分离代码与散文

## 问题定义

当前批改模块的错误演示功能中，`sourceCode` 字段包含整页报告文字而非纯代码，导致：
- 错误标注位置错误（标记在报告标题而非代码行）
- 演示页面显示散文而非代码
- 学生无法理解真正的代码错误

## 目标

在 Worker 的证据提取阶段，识别 PDF 页面中的代码区域，将其单独存储为 `code` 类型证据块，使错误演示只显示代码并正确标注错误行。

## 范围

- 修改 `grading-worker/pipeline/document_parser.py` 或新增 `grading-worker/pipeline/code_block_extractor.py`
- 修改 `grading-worker/tasks.py` 的证据生成逻辑
- 修改 `backend-repo` 的 `GradingErrorDemonstrationService` 优先使用 `kind='code'` 证据块
- 前端 `ErrorDemonstrationPlayer.vue` 无需改动（继续使用 `sourceCode` 和 `errorRanges`）

## 设计方案

### 1. Worker 代码块提取

#### 1.1 提取策略

采用**混合策略**：启发式规则优先，VLM 兜底。

**启发式规则**（零成本，处理 80% 场景）：
- 代码关键词检测：`#include`, `int `, `void `, `char `, `float `, `double `, `for`, `while`, `if`, `else`, `return`, `printf`, `scanf`, `malloc`, `free`, `struct`, `enum`, `typedef`
- 代码结构检测：大括号 `{}`、分号 `;`、赋值 `=`、注释 `//` 或 `/*`
- 代码密度：连续 3 行以上包含代码关键词或结构符号

**VLM 兜底**（处理复杂布局）：
- 当启发式规则无法确定时，调用 VLM `task="extract_code"` 识别代码区域
- VLM 返回 `{"code_regions": [{"start_line": 5, "end_line": 15, "code": "..."}]}`

#### 1.2 数据模型

新增 `CodeBlock` 数据类：

```python
@dataclass
class CodeBlock:
    page: int
    start_line: int  # 在原始证据块中的起始行号
    end_line: int    # 在原始证据块中的结束行号
    code: str        # 纯代码内容
    language: str    # 'c', 'python', 'java', 'unknown'
    confidence: float
```

#### 1.3 证据块存储

- 代码块存为 `EvidenceBlock(kind='code', content=code, page=page, bbox=bbox)`
- 保留原始文本证据块 `kind='text'`，不删除
- 代码块和文本块共存，动画生成优先使用 `kind='code'`

### 2. 后端动画生成

#### 2.1 证据选择

`GradingErrorDemonstrationService` 修改证据选择逻辑：

```java
// 优先选择 kind='code' 的证据块
List<EvidenceBlockEntity> codeBlocks = evidenceRepo.findAllBySubmissionIdAndKind(submissionId, "code");
if (!codeBlocks.isEmpty()) {
    // 使用代码块生成动画
} else {
    // 回退到 text 证据块（现有逻辑）
}
```

#### 2.2 错误标注

`errorRanges` 的行号映射到代码块内部行号，而不是原始证据块行号：

```java
// 计算代码块内部行号
int codeBlockLine = originalLine - codeBlock.startLine + 1;
```

### 3. 实现细节

#### 3.1 Worker 修改

**文件**: `grading-worker/pipeline/code_block_extractor.py`（新增）

```python
"""从证据块内容中提取代码块。"""
import re
from dataclasses import dataclass
from typing import List

@dataclass
class CodeBlock:
    page: int
    start_line: int
    end_line: int
    code: str
    language: str
    confidence: float

CODE_KEYWORDS = [
    '#include', '#define', 'int ', 'void ', 'char ', 'float ', 'double ',
    'bool ', 'for', 'while', 'if', 'else', 'switch', 'case', 'break',
    'continue', 'return', 'printf', 'scanf', 'malloc', 'free', 'calloc',
    'realloc', 'struct ', 'enum ', 'typedef', 'const ', 'static ', 'extern ',
    '->', '->*', '::', 'std::', 'size_t', 'uint16_t', 'uint32_t', 'int16_t',
    'int32_t', 'uint8_t', 'int8_t', 'HAL_', 'GPIO_', 'ADC_', 'TIM_', 'USART_'
]

CODE_SYMBOLS = ['{', '}', ';', '=', '(', ')', '[', ']', '->', '++', '--', '+=', '-=', '*=', '/=', '&=', '|=', '^=', '~', '!', '&&', '||']

def extract_code_blocks(content: str, page: int) -> List[CodeBlock]:
    """从文本内容中提取代码块。"""
    if not content:
        return []
    
    lines = content.split('\n')
    blocks = []
    current_block = None
    current_start = 0
    
    for i, line in enumerate(lines):
        if _looks_like_code(line):
            if current_block is None:
                current_start = i
                current_block = [line]
            else:
                current_block.append(line)
        else:
            if current_block is not None and len(current_block) >= 3:
                # 至少 3 行才算代码块
                blocks.append(CodeBlock(
                    page=page,
                    start_line=current_start + 1,
                    end_line=i,
                    code='\n'.join(current_block),
                    language=_detect_language(current_block),
                    confidence=_calculate_confidence(current_block)
                ))
            current_block = None
    
    # 处理文件末尾的代码块
    if current_block is not None and len(current_block) >= 3:
        blocks.append(CodeBlock(
            page=page,
            start_line=current_start + 1,
            end_line=len(lines),
            code='\n'.join(current_block),
            language=_detect_language(current_block),
            confidence=_calculate_confidence(current_block)
        ))
    
    return blocks

def _looks_like_code(line: str) -> bool:
    """判断一行是否像代码。"""
    stripped = line.strip()
    if not stripped:
        return False
    
    # 代码关键词
    if any(kw in stripped for kw in CODE_KEYWORDS):
        return True
    
    # 代码符号密度
    symbol_count = sum(1 for s in CODE_SYMBOLS if s in stripped)
    if symbol_count >= 2:
        return True
    
    # 注释
    if stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*'):
        return True
    
    # 缩进 + 赋值/调用
    if line.startswith(' ') or line.startswith('\t'):
        if any(s in stripped for s in ['=', '(', ')', ';']):
            return True
    
    return False

def _detect_language(lines: List[str]) -> str:
    """检测代码语言。"""
    text = '\n'.join(lines)
    if '#include' in text or 'printf' in text or 'scanf' in text:
        return 'c'
    if 'def ' in text or 'import ' in text or 'print(' in text:
        return 'python'
    if 'public ' in text or 'private ' in text or 'System.out' in text:
        return 'java'
    return 'unknown'

def _calculate_confidence(lines: List[str]) -> float:
    """计算代码块置信度。"""
    if not lines:
        return 0.0
    code_lines = sum(1 for line in lines if _looks_like_code(line))
    return code_lines / len(lines)
```

**文件**: `grading-worker/tasks.py`（修改）

在证据生成逻辑中，为每个 `kind='text'` 的证据块调用 `extract_code_blocks`，并将提取出的代码块作为额外的 `EvidenceBlock(kind='code')` 保存。

```python
from pipeline.code_block_extractor import extract_code_blocks

# 在生成 text 证据块后
text_blocks = [b for b in evidence_blocks if b.kind == 'text']
for tb in text_blocks:
    code_blocks = extract_code_blocks(tb.content, tb.page)
    for cb in code_blocks:
        ev_counter += 1
        evidence_blocks.append(EvidenceBlock(
            evidence_id=f"ev-{submission_id}-{ev_counter:04d}",
            kind='code',
            page=cb.page,
            content=cb.code,
            confidence=cb.confidence,
            bbox_json=None
        ))
```

#### 3.2 后端修改

**文件**: `GradingErrorDemonstrationService.java`（修改）

```java
private List<EvidenceBlockEntity> selectEvidenceBlocksForAnimation(Long submissionId) {
    // 优先使用 code 证据块
    List<EvidenceBlockEntity> codeBlocks = evidenceRepo.findAllBySubmissionIdAndKind(submissionId, "code");
    if (!codeBlocks.isEmpty()) {
        return codeBlocks;
    }
    // 回退到 text 证据块
    return evidenceRepo.findAllBySubmissionIdAndKind(submissionId, "text");
}
```

#### 3.3 数据库变更

无新表。仅使用现有 `evidence_block.kind` 字段，新增 `'code'` 值。

## 非目标

- 不修改前端展示逻辑（继续使用现有 `ErrorDemonstrationPlayer`）
- 不处理历史已批改数据（新提取只对新提交生效，历史数据可选重新批改）
- 不实现跨页代码块合并（后续优化）

## 验证方式

1. 上传一份包含 C 代码的学生报告
2. 检查 `evidence_block` 表中是否出现 `kind='code'` 的记录
3. 生成错误演示，检查 `sourceCode` 是否只包含代码
4. 检查 `errorRanges` 是否指向正确的代码行号
5. 前端展示是否只显示代码并正确高亮错误行

## 风险与兜底

- **风险：启发式规则误报/漏报**
  - 兜底：VLM `extract_code` 任务，或保留现有 text 证据块回退
- **风险：代码块跨页**
  - 兜底：按页分别提取，不合并跨页代码
- **风险：历史数据无 code 证据块**
  - 兜底：动画生成逻辑回退到 text 证据块，行为与当前一致
