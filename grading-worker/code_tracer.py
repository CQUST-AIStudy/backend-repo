#!/usr/bin/env python3
"""
Generic code execution tracer.

Supports:
  - C code via source instrumentation + gcc
  - Python code via sys.settrace

Usage:
  python3.11 code_tracer.py c source.c [--stdin "input"]
  python3.11 code_tracer.py python source.py [--stdin "input"]

Output: JSON trace compatible with Python Tutor-style visualizers.
"""

import argparse
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


# --- 轻量沙箱加固：对编译/运行子进程施加资源限制并截断输出（仅 POSIX 生效） ---
def _rlimit_preexec():  # pragma: no cover - 仅 Linux 运行期生效
    """在 fork 后、exec 前对子进程设置 CPU/内存/文件大小上限，挡住死循环/内存炸/写大文件。"""
    import resource

    resource.setrlimit(resource.RLIMIT_CPU, (5, 6))
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024 * 1024, 8 * 1024 * 1024))


_PREEXEC = _rlimit_preexec if hasattr(os, "fork") else None
_OUTPUT_CAP = 256 * 1024


def _cap(text):
    """截断过长输出，防止内存/传输膨胀。"""
    if text is None or len(text) <= _OUTPUT_CAP:
        return text
    return text[:_OUTPUT_CAP] + "\n...[truncated]"



# ---------------------------------------------------------------------------
# Common data structures
# ---------------------------------------------------------------------------

@dataclass
class TraceStep:
    step: int
    line: int
    stdout: str = ""
    locals_: dict[str, Any] = field(default_factory=dict)
    globals_: dict[str, Any] = field(default_factory=dict)
    heap: dict[str, Any] = field(default_factory=dict)
    error: bool = False
    error_message: str = ""
    event: str = "step"

    def to_dict(self) -> dict:
        return {
            "step": self.step,
            "line": self.line,
            "stdout": self.stdout,
            "locals": self.locals_,
            "globals": self.globals_,
            "heap": self.heap,
            "error": self.error,
            "errorMessage": self.error_message,
            "event": self.event,
        }


@dataclass
class TraceResult:
    success: bool
    language: str
    source_code: str
    steps: list[TraceStep]
    error_message: str = ""
    stdout: str = ""
    stderr: str = ""

    def to_dict(self) -> dict:
        return {
            "success": self.success,
            "language": self.language,
            "sourceCode": self.source_code,
            "steps": [s.to_dict() for s in self.steps],
            "errorMessage": self.error_message,
            "stdout": self.stdout,
            "stderr": self.stderr,
        }


# ---------------------------------------------------------------------------
# C tracer
# ---------------------------------------------------------------------------

class CTracer:
    """Trace C code execution by source instrumentation."""

    def __init__(self, source_code: str, stdin_text: str = ""):
        self.source_code = source_code
        self.stdin_text = stdin_text

    def trace(self) -> TraceResult:
        try:
            instrumented = self._instrument(self.source_code)
        except Exception as e:
            return TraceResult(
                success=False,
                language="c",
                source_code=self.source_code,
                steps=[],
                error_message=f"Instrumentation failed: {e}",
            )

        with tempfile.TemporaryDirectory(prefix="c_trace_") as tmpdir:
            src_path = Path(tmpdir) / "main.c"
            exe_path = Path(tmpdir) / "main.exe"
            src_path.write_text(instrumented, encoding="utf-8")

            # Compile
            compile_cmd = ["gcc", "-g", "-O0", "-o", str(exe_path), str(src_path)]
            compile_res = subprocess.run(
                compile_cmd, capture_output=True, text=True, timeout=30, preexec_fn=_PREEXEC
            )
            if compile_res.returncode != 0:
                return TraceResult(
                    success=False,
                    language="c",
                    source_code=self.source_code,
                    steps=[],
                    stdout=_cap(compile_res.stdout),
                    stderr=_cap(compile_res.stderr),
                    error_message=f"Compilation failed:\n{_cap(compile_res.stderr)}",
                )

            # Run
            run_cmd = [str(exe_path)]
            try:
                run_res = subprocess.run(
                    run_cmd,
                    input=self.stdin_text,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    preexec_fn=_PREEXEC,
                )
            except subprocess.TimeoutExpired:
                return TraceResult(
                    success=False,
                    language="c",
                    source_code=self.source_code,
                    steps=[],
                    error_message="Execution timeout (possible infinite loop)",
                )

            # Parse trace from stderr（先截断，防止超大输出撑爆内存/传输）
            run_stdout = _cap(run_res.stdout)
            run_stderr = _cap(run_res.stderr)
            steps = self._parse_trace(run_stderr, self.source_code)

            # If program crashed, mark last step as error
            if run_res.returncode != 0 and steps:
                steps[-1].error = True
                steps[-1].error_message = self._describe_exit_code(run_res.returncode)

            return TraceResult(
                success=True,
                language="c",
                source_code=self.source_code,
                steps=steps,
                stdout=run_stdout,
                stderr=run_stderr,
            )

    def _instrument(self, code: str) -> str:
        """
        Insert trace calls into C source.

        Strategy:
        - Normalize单语句 if/else/for/while 体为带花括号形式（保行号），
          避免在 if 与 else 之间插入语句破坏控制流。
        - Maintain a stack of scopes based on '{' and '}'.
        - Track variable declarations in the current scope.
        - After each executable line, emit __trace_line() and __trace_vars().
        """
        code = self._normalize_braces(code)
        lines = code.splitlines()
        scope_stack: list[list[dict[str, str]]] = [[]]  # global/function-level scope
        output_lines: list[str] = []
        # for(int i=...) 的循环变量应归属循环体作用域；当 '{' 在后续行时，
        # 先暂存，等下一个 '{' 打开作用域时再注入，避免泄漏到外层导致 undeclared。
        pending_for_init: list[dict[str, str]] = []

        # Insert runtime header at top
        output_lines.extend(self._runtime_header().splitlines())
        output_lines.append("")

        for i, raw_line in enumerate(lines):
            line_no = i + 1
            stripped = raw_line.strip()

            # Close scopes BEFORE processing this line: variables in blocks ending
            # here are no longer available after this line.
            close_count = raw_line.count('}')
            for _ in range(close_count):
                if len(scope_stack) > 1:
                    scope_stack.pop()

            # Open new scopes for '{' on this line.
            open_count = raw_line.count('{')
            for _ in range(open_count):
                new_scope: list[dict[str, str]] = []
                if pending_for_init:
                    new_scope.extend(pending_for_init)
                    pending_for_init = []
                scope_stack.append(new_scope)

            # Extract declarations into current (innermost) scope.
            decls = self._extract_declarations(stripped, line_no)
            for decl in decls:
                # for-init 且本行未开花括号：暂存到下一个作用域（循环体）。
                if decl.get("for_init") and open_count == 0:
                    pending_for_init.append(decl)
                else:
                    scope_stack[-1].append(decl)

            # Only emit trace calls inside a function body (scope depth > 1).
            # Trace before function definitions would appear at global scope and fail to compile.
            if len(scope_stack) > 1 and self._should_instrument(stripped):
                flat_scope = [var for scope in scope_stack for var in scope]
                trace_call = self._build_trace_call(line_no, stripped, flat_scope)
                if trace_call:
                    output_lines.append(trace_call)

            output_lines.append(raw_line)

        return "\n".join(output_lines)

    def _normalize_braces(self, code: str) -> str:
        """
        为单语句的 if/else if/else/for/while/do 体补上花括号，插入位置：
        '{' 紧跟在控制头（')' 或 else/do 关键字）之后，'}' 紧跟在体语句结尾之后。
        仅插入单字符、不新增行，因此源代码行号保持不变（前端展示的行号仍然对应）。

        识别错误只会导致插桩后编译失败 -> 上层优雅回退，不会产生错误的可视化。
        """
        n = len(code)
        if n == 0:
            return code

        def is_ident(ch: str) -> bool:
            return ch.isalnum() or ch == '_'

        def skip_string(i: int) -> int:
            q = code[i]
            i += 1
            while i < n:
                if code[i] == '\\':
                    i += 2
                    continue
                if code[i] == q:
                    return i + 1
                i += 1
            return i

        def skip_comment(i: int) -> int:
            if code[i + 1] == '/':
                i += 2
                while i < n and code[i] != '\n':
                    i += 1
                return i
            i += 2
            while i + 1 < n and not (code[i] == '*' and code[i + 1] == '/'):
                i += 1
            return i + 2

        def skip_ws(i: int) -> int:
            while i < n:
                ch = code[i]
                if ch in ' \t\r\n':
                    i += 1
                elif ch == '/' and i + 1 < n and code[i + 1] in '/*':
                    i = skip_comment(i)
                else:
                    break
            return i

        def match_paren(i: int) -> int:
            depth = 0
            while i < n:
                ch = code[i]
                if ch in '"\'':
                    i = skip_string(i)
                    continue
                if ch == '/' and i + 1 < n and code[i + 1] in '/*':
                    i = skip_comment(i)
                    continue
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
            return -1

        def match_brace(i: int) -> int:
            depth = 0
            while i < n:
                ch = code[i]
                if ch in '"\'':
                    i = skip_string(i)
                    continue
                if ch == '/' and i + 1 < n and code[i + 1] in '/*':
                    i = skip_comment(i)
                    continue
                if ch == '{':
                    depth += 1
                elif ch == '}':
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
            return -1

        def find_stmt_end(i: int) -> int:
            """返回从 i 处开始的单条语句结束后的下标（exclusive）。"""
            i = skip_ws(i)
            if i >= n:
                return n
            ch = code[i]
            if ch == '{':
                m = match_brace(i)
                return m + 1 if m != -1 else n
            if ch.isalpha() or ch == '_':
                j = i
                while j < n and is_ident(code[j]):
                    j += 1
                word = code[i:j]
                if word in ('if', 'for', 'while', 'switch'):
                    k = skip_ws(j)
                    if k < n and code[k] == '(':
                        k = match_paren(k)
                        if k != -1:
                            body_end = find_stmt_end(k + 1)
                            if word == 'if':
                                e = skip_ws(body_end)
                                if code[e:e + 4] == 'else' and (e + 4 >= n or not is_ident(code[e + 4])):
                                    return find_stmt_end(e + 4)
                            return body_end
                elif word == 'do':
                    body_end = find_stmt_end(j)
                    e = skip_ws(body_end)
                    if code[e:e + 5] == 'while':
                        e2 = skip_ws(e + 5)
                        if e2 < n and code[e2] == '(':
                            e2 = match_paren(e2)
                            if e2 != -1:
                                e3 = skip_ws(e2 + 1)
                                if e3 < n and code[e3] == ';':
                                    return e3 + 1
                    return body_end
                elif word == 'else':
                    return find_stmt_end(j)
            # 普通语句：扫描到括号深度 0 处的 ';'
            depth = 0
            k = i
            while k < n:
                c = code[k]
                if c in '"\'':
                    k = skip_string(k)
                    continue
                if c == '/' and k + 1 < n and code[k + 1] in '/*':
                    k = skip_comment(k)
                    continue
                if c in '([':
                    depth += 1
                elif c in ')]':
                    depth -= 1
                elif c == '{':
                    m = match_brace(k)
                    return m + 1 if m != -1 else n
                elif c == ';' and depth == 0:
                    return k + 1
                k += 1
            return n

        opens: dict[int, int] = {}
        closes: dict[int, int] = {}

        def wrap(header_end: int, body_scan_start: int) -> None:
            b = skip_ws(body_scan_start)
            if b >= n or code[b] == '{' or code[b] == ';':
                return  # 已有花括号 / 空体，无需处理
            end = find_stmt_end(b)
            opens[header_end] = opens.get(header_end, 0) + 1
            closes[end] = closes.get(end, 0) + 1

        i = 0
        while i < n:
            ch = code[i]
            if ch in '"\'':
                i = skip_string(i)
                continue
            if ch == '/' and i + 1 < n and code[i + 1] in '/*':
                i = skip_comment(i)
                continue
            if ch.isalpha() or ch == '_':
                j = i
                while j < n and is_ident(code[j]):
                    j += 1
                word = code[i:j]
                prev = code[i - 1] if i > 0 else ''
                if word in ('if', 'for', 'while') and not is_ident(prev):
                    k = skip_ws(j)
                    if k < n and code[k] == '(':
                        close = match_paren(k)
                        if close != -1:
                            wrap(close + 1, close + 1)
                            i = close + 1
                            continue
                elif word == 'else' and not is_ident(prev):
                    k = skip_ws(j)
                    # else if -> 交给 if 处理；else { -> 已有花括号；else stmt -> 补花括号
                    if not (code[k:k + 2] == 'if' and (k + 2 >= n or not is_ident(code[k + 2]))):
                        wrap(j, j)
                    i = j
                    continue
                elif word == 'do' and not is_ident(prev):
                    wrap(j, j)
                    i = j
                    continue
                i = j
                continue
            i += 1

        if not opens and not closes:
            return code
        out: list[str] = []
        for pos in range(n + 1):
            if pos in closes:
                out.append('}' * closes[pos])
            if pos in opens:
                out.append('{' * opens[pos])
            if pos < n:
                out.append(code[pos])
        return ''.join(out)

    def _runtime_header(self) -> str:
        return r'''
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef C_TRACE_RUNTIME_H
#define C_TRACE_RUNTIME_H

static int __trace_enabled = 1;

static void __trace_init(void) __attribute__((constructor));
static void __trace_init(void) {
    setbuf(stderr, NULL);
}

static void __trace_line(int line) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_LINE__ %d\n", line);
}

static void __trace_var_int(const char* name, int val) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_VAR__ %s int %d\n", name, val);
}

static void __trace_var_long(const char* name, long val) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_VAR__ %s long %ld\n", name, val);
}

static void __trace_var_float(const char* name, float val) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_VAR__ %s float %f\n", name, val);
}

static void __trace_var_double(const char* name, double val) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_VAR__ %s double %f\n", name, val);
}

static void __trace_var_char(const char* name, char val) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_VAR__ %s char %d\n", name, (int)val);
}

static void __trace_var_ptr(const char* name, void* val) {
    if (!__trace_enabled) return;
    if (val) fprintf(stderr, "__TRACE_VAR__ %s ptr %p\n", name, val);
    else fprintf(stderr, "__TRACE_VAR__ %s ptr NULL\n", name);
}

static void __trace_array_int(const char* name, int* arr, int size) {
    if (!__trace_enabled) return;
    fprintf(stderr, "__TRACE_ARR_START__ %s int %d", name, size);
    for (int __i = 0; __i < size && __i < 20; __i++) {
        fprintf(stderr, " %d", arr[__i]);
    }
    fprintf(stderr, "\n");
}

#define __TRACE_STRINGIFY(x) #x
#define __TRACE_TOSTRING(x) __TRACE_STRINGIFY(x)

#endif
'''

    def _extract_declarations(self, stripped: str, line_no: int) -> list[dict[str, str]]:
        """Extract variable declarations from a single line of C code."""
        decls: list[dict[str, str]] = []
        if not stripped or stripped.startswith("//") or stripped.startswith("#"):
            return decls

        # for-loop init clauses, e.g. for (int i = 0; ...)
        # Mark with decl_line so we don't try to read them on the for-header line itself.
        for_init_match = re.search(r"for\s*\(\s*(int|float|double|char|long)\s+\*?\s*([a-zA-Z_]\w*)\s*=", stripped)
        if for_init_match:
            decls.append({
                "name": for_init_match.group(2),
                "type": for_init_match.group(1),
                "kind": "scalar",
                "decl_line": str(line_no),
                "for_init": True,
            })

        # Normal declarations: int x; int* p; int arr[10];
        # Negative lookahead for '(' avoids matching function definitions like int main()
        m = re.match(r"^\s*(int|float|double|char|long)\s+\*?\s*([a-zA-Z_]\w*)\b(?!\s*\()", stripped)
        if m:
            var_type = m.group(1)
            var_name = m.group(2)
            # Skip function definitions (contains '(' before '=' or ';')
            before_eq = stripped.split("=")[0]
            if "(" not in before_eq:
                is_pointer = "*" in stripped[:stripped.find(var_name) + 1]
                is_array = "[" in stripped
                if is_array:
                    arr_match = re.search(r"\[\s*(\d+)\s*\]", stripped)
                    size = int(arr_match.group(1)) if arr_match else 10
                    decls.append({
                        "name": var_name,
                        "type": var_type,
                        "kind": "array",
                        "size": str(size),
                        "decl_line": str(line_no),
                    })
                elif is_pointer:
                    decls.append({
                        "name": var_name,
                        "type": var_type,
                        "kind": "pointer",
                        "decl_line": str(line_no),
                    })
                else:
                    decls.append({
                        "name": var_name,
                        "type": var_type,
                        "kind": "scalar",
                        "decl_line": str(line_no),
                    })

        return decls

    def _should_instrument(self, stripped: str) -> bool:
        if not stripped:
            return False
        if stripped.startswith("//"):
            return False
        if stripped.startswith("#"):
            return False
        if stripped in {"{", "}", "};"}:
            return False
        if stripped.startswith("}"):
            return False
        # 控制流延续行不插桩：在 'else' / 'else if' 前插入语句会打断 if/else 配对。
        if re.match(r"^\}?\s*else\b", stripped):
            return False
        # Don't instrument declarations without executable effect unless they have initializer
        if re.match(r"^(int|float|double|char|long)\s+\w+\s*;\s*$", stripped):
            return False
        # Skip function definitions (e.g. int main() {)
        if re.match(r"^(int|void|float|double|char|long|short|unsigned|signed|static|extern)\s+\w+\s*\(", stripped):
            return False
        return True

    def _build_trace_call(self, line_no: int, stripped: str, scope: list[dict[str, str]]) -> str:
        calls: list[str] = []
        # Emit variable traces BEFORE line trace, so the captured state reflects
        # the program state at the start of this line.
        if stripped != "}" and not stripped.startswith("}"):
            for var in scope:
                name = var["name"]
                kind = var["kind"]
                vtype = var["type"]
                # Skip variables declared on the current line (not in scope before the line executes)
                if var.get("decl_line") == str(line_no):
                    continue
                if kind == "array":
                    if vtype == "int":
                        calls.append(f'__trace_array_int("{name}", {name}, {var.get("size", "10")});')
                elif kind == "pointer":
                    calls.append(f'__trace_var_ptr("{name}", (void*){name});')
                elif vtype == "int":
                    calls.append(f'__trace_var_int("{name}", {name});')
                elif vtype == "long":
                    calls.append(f'__trace_var_long("{name}", {name});')
                elif vtype == "float":
                    calls.append(f'__trace_var_float("{name}", {name});')
                elif vtype == "double":
                    calls.append(f'__trace_var_double("{name}", {name});')
                elif vtype == "char":
                    calls.append(f'__trace_var_char("{name}", {name});')
        # Line trace comes last so the step captures all variables for this line.
        calls.append(f"__trace_line({line_no});")
        return " ".join(calls)

    def _parse_trace(self, stderr_text: str, source_code: str) -> list[TraceStep]:
        source_lines = source_code.splitlines()
        steps: list[TraceStep] = []
        current_stdout = ""
        current_vars: dict[str, Any] = {}
        current_line = 1
        step_no = 0

        # Also capture program stdout from the subprocess itself, not trace
        # The TraceResult.stdout already has it; here we just build steps.

        for raw in stderr_text.splitlines():
            line = raw.strip()
            if line.startswith("__TRACE_LINE__"):
                parts = line.split()
                if len(parts) >= 2:
                    try:
                        current_line = int(parts[1])
                    except ValueError:
                        pass
                    step_no += 1
                    steps.append(TraceStep(
                        step=step_no,
                        line=current_line,
                        locals_=current_vars.copy(),
                    ))
            elif line.startswith("__TRACE_VAR__"):
                parts = line.split(maxsplit=3)
                if len(parts) >= 4:
                    name = parts[1]
                    vtype = parts[2]
                    value = parts[3]
                    current_vars[name] = self._parse_value(vtype, value)
            elif line.startswith("__TRACE_ARR_START__"):
                parts = line.split()
                if len(parts) >= 4:
                    name = parts[1]
                    size = int(parts[3])
                    values = [self._parse_value("int", v) for v in parts[4:]]
                    current_vars[name] = {"type": "array", "size": size, "values": values}

        # If no trace steps but program ran, add at least one step at line 1
        if not steps:
            step_no += 1
            steps.append(TraceStep(step=step_no, line=1))

        return steps

    def _describe_exit_code(self, code: int) -> str:
        # Windows access violation
        if code == 3221225477 or code == -1073741819:
            return "程序访问了无效内存地址（段错误）"
        # Common Unix segfault via Git Bash
        if code == 139:
            return "程序触发段错误（Segmentation fault）"
        if code == 138:
            return "程序被异常终止（SIGBUS）"
        if code < 0:
            return f"程序异常终止（exit code {code}）"
        return f"程序以非零状态退出（exit code {code}）"

    def _parse_value(self, vtype: str, value: str) -> Any:
        if vtype == "int" or vtype == "long":
            try:
                return int(value)
            except ValueError:
                return value
        if vtype == "float" or vtype == "double":
            try:
                return float(value)
            except ValueError:
                return value
        if vtype == "char":
            try:
                return chr(int(value))
            except ValueError:
                return value
        if vtype == "ptr":
            if value == "NULL":
                return None
            return value
        return value


# ---------------------------------------------------------------------------
# Python tracer
# ---------------------------------------------------------------------------

class PythonTracer:
    """Trace Python code execution via sys.settrace."""

    def __init__(self, source_code: str, stdin_text: str = ""):
        self.source_code = source_code
        self.stdin_text = stdin_text

    def trace(self) -> TraceResult:
        # Save and restore stdin/stdout
        old_stdin = sys.stdin
        old_stdout = sys.stdout
        old_stderr = sys.stderr

        stdout_capture = io.StringIO()
        stderr_capture = io.StringIO()

        sys.stdin = io.StringIO(self.stdin_text)
        sys.stdout = stdout_capture
        sys.stderr = stderr_capture

        steps: list[TraceStep] = []
        step_no = 0
        last_error: str = ""

        def trace_func(frame, event, arg):
            nonlocal step_no, last_error
            if event not in ("line", "return", "exception"):
                return trace_func

            filename = frame.f_code.co_filename
            if "<string>" not in filename and filename != "__main__":
                return trace_func

            line_no = frame.f_lineno
            step_no += 1

            locals_ = {}
            for name, val in frame.f_locals.items():
                if name.startswith("__"):
                    continue
                locals_[name] = self._serialize(val)

            globals_ = {}
            for name, val in frame.f_globals.items():
                if name.startswith("__"):
                    continue
                globals_[name] = self._serialize(val)

            heap = {}

            is_error = event == "exception"
            if is_error and arg:
                exc_type, exc_value, _ = arg
                last_error = f"{exc_type.__name__}: {exc_value}"

            steps.append(TraceStep(
                step=step_no,
                line=line_no,
                stdout=stdout_capture.getvalue(),
                locals_=locals_,
                globals_=globals_,
                heap=heap,
                error=is_error,
                error_message=last_error,
                event=event,
            ))

            return trace_func

        sys.settrace(trace_func)
        try:
            compiled = compile(self.source_code, "<string>", "exec")
            exec(compiled, {"__name__": "__main__"})
        except Exception as e:
            last_error = f"{type(e).__name__}: {e}"
        finally:
            sys.settrace(None)
            sys.stdin = old_stdin
            sys.stdout = old_stdout
            sys.stderr = old_stderr

        return TraceResult(
            success=True,
            language="python",
            source_code=self.source_code,
            steps=steps,
            stdout=stdout_capture.getvalue(),
            stderr=stderr_capture.getvalue(),
            error_message=last_error,
        )

    def _serialize(self, val: Any) -> Any:
        if val is None:
            return None
        if isinstance(val, (int, float, str, bool)):
            return val
        if isinstance(val, list):
            return {"type": "list", "size": len(val), "values": [self._serialize(v) for v in val[:20]]}
        if isinstance(val, dict):
            return {"type": "dict", "size": len(val), "items": [{self._serialize(k): self._serialize(v)} for k, v in list(val.items())[:10]]}
        if isinstance(val, tuple):
            return {"type": "tuple", "size": len(val), "values": [self._serialize(v) for v in val[:20]]}
        return {"type": type(val).__name__, "repr": repr(val)[:100]}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Generic code execution tracer")
    parser.add_argument("language", choices=["c", "python"], help="Language to trace")
    parser.add_argument("source", help="Path to source file or literal source code")
    parser.add_argument("--stdin", default="", help="Standard input for the program")
    parser.add_argument("--source-is-string", action="store_true",
                        help="If set, source is treated as literal code instead of file path")

    args = parser.parse_args()

    if args.source_is_string:
        source_code = args.source
    else:
        source_code = Path(args.source).read_text(encoding="utf-8")

    try:
        if args.language == "c":
            tracer = CTracer(source_code, args.stdin)
        else:
            tracer = PythonTracer(source_code, args.stdin)

        result = tracer.trace()
        output = json.dumps(result.to_dict(), ensure_ascii=False, indent=2)
        sys.stdout.buffer.write(output.encode("utf-8"))
    except Exception as e:
        output = json.dumps({
            "success": False,
            "language": args.language,
            "sourceCode": source_code if 'source_code' in locals() else args.source,
            "steps": [],
            "errorMessage": f"Tracer crashed: {e}\n{traceback.format_exc()}",
        }, ensure_ascii=False, indent=2)
        sys.stderr.buffer.write(output.encode("utf-8"))
        sys.exit(1)


if __name__ == "__main__":
    main()
