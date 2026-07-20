"""从证据块内容中提取代码块。

启发式规则优先识别代码区域，VLM 可作为后续兜底扩展。
"""
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
    'int32_t', 'uint8_t', 'int8_t', 'HAL_', 'GPIO_', 'ADC_', 'TIM_', 'USART_',
    'SPI_', 'I2C_', 'CAN_', 'PWM_', 'DMA_', 'RCC_'
]

CODE_SYMBOLS = ['{', '}', ';', '=', '(', ')', '[', ']', '->', '++', '--', '+=', '-=', '*=', '/=', '&=', '|=', '^=', '~', '!', '&&', '||']


def extract_code_blocks(content: str, page: int, min_lines: int = 3) -> List[CodeBlock]:
    """从文本内容中提取代码块。

    使用代码密度滑动窗口：连续代码行之间允许最多 1 个空行，
    只有连续 2 行以上明确是散文时才终止代码块。

    Args:
        content: 原始文本内容
        page: 页码
        min_lines: 最小代码行数，少于该值的代码片段会被丢弃

    Returns:
        提取出的代码块列表
    """
    if not content:
        return []

    lines = content.split('\n')
    blocks = []
    current_block = None
    current_start = 0
    blank_count = 0

    def flush_block(end_idx: int):
        nonlocal current_block, current_start
        if current_block is not None and len(current_block) >= min_lines:
            blocks.append(CodeBlock(
                page=page,
                start_line=current_start + 1,
                end_line=end_idx,
                code='\n'.join(current_block),
                language=_detect_language(current_block),
                confidence=_calculate_confidence(current_block)
            ))
        current_block = None

    for i, line in enumerate(lines):
        stripped = line.strip()
        is_code = _looks_like_code(line)
        is_blank = not stripped

        if is_code:
            blank_count = 0
            if current_block is None:
                current_start = i
                current_block = [line]
            else:
                current_block.append(line)
        elif is_blank and current_block is not None:
            # 空行可以出现在代码块中间，但连续 2 个空行可能意味着代码结束
            blank_count += 1
            if blank_count <= 1:
                current_block.append(line)
            else:
                flush_block(i - blank_count + 1)
                blank_count = 0
        else:
            # 明确的散文行，终止代码块
            flush_block(i)
            blank_count = 0

    # 处理文件末尾的代码块
    if current_block is not None and len(current_block) >= min_lines:
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

    # 结构符号行（如 }, {, );, ;, }); 等）
    if stripped in ('}', '{', ');', '});', '};', '];', ');'):
        return True

    # 代码符号密度（至少 2 个符号）
    symbol_count = sum(1 for s in CODE_SYMBOLS if s in stripped)
    if symbol_count >= 2:
        return True

    # 注释
    if stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*'):
        return True

    # 缩进 + 赋值/调用/结束符
    if line.startswith(' ') or line.startswith('\t'):
        if any(s in stripped for s in ['=', '(', ')', ';', ',']):
            return True

    return False


def _detect_language(lines: List[str]) -> str:
    """检测代码语言。"""
    text = '\n'.join(lines)
    if '#include' in text or 'printf' in text or 'scanf' in text or 'malloc' in text:
        return 'c'
    if 'def ' in text or 'import ' in text or 'print(' in text or 'self.' in text:
        return 'python'
    if 'public ' in text or 'private ' in text or 'System.out' in text or 'void main' in text:
        return 'java'
    return 'unknown'


def _calculate_confidence(lines: List[str]) -> float:
    """计算代码块置信度（0.0-1.0）。"""
    if not lines:
        return 0.0
    code_lines = sum(1 for line in lines if _looks_like_code(line))
    return round(code_lines / len(lines), 2)
