"""Terminal theme and layout helpers."""

import re

ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")
RST, BOLD, DIM = "\033[0m", "\033[1m", "\033[2m"


def fg(number: int) -> str:
    return f"\033[38;5;{number}m"


def bg(number: int) -> str:
    return f"\033[48;5;{number}m"


# Light blue, yellow, green, pink, and soft neutral colors.
GREEN, RED, YEL, CYAN = fg(120), fg(203), fg(229), fg(117)
BLUE, MAG, GREY, WHITE = fg(153), fg(219), fg(246), fg(255)


def visible_length(text: str) -> int:
    return len(ANSI_RE.sub("", text))


def clip(text: str, width: int) -> str:
    output, count, index = [], 0, 0
    while index < len(text):
        match = ANSI_RE.match(text, index)
        if match:
            output.append(match.group())
            index = match.end()
            continue
        if count >= width:
            output.append(RST)
            break
        output.append(text[index])
        index += 1
        count += 1
    return "".join(output)


def fit(text: str, width: int) -> str:
    clipped = clip(text, width)
    return clipped + " " * max(0, width - visible_length(clipped))


def box(title: str, lines: list, width: int, height: int, accent: str = CYAN) -> list:
    label = f" {title} "
    rows = [accent + "╭─" + BOLD + label + RST + accent +
            "─" * max(0, width - 4 - len(label)) + "╮" + RST]
    edge = accent + "│" + RST
    for line in lines[:height - 2]:
        rows.append(edge + " " + fit(line, width - 4) + RST + " " + edge)
    rows.extend(edge + " " * (width - 2) + edge for _ in range(max(0, height - 2 - len(lines))))
    rows.append(accent + "╰" + "─" * (width - 2) + "╯" + RST)
    return rows
