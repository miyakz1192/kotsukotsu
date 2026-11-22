"""日本の国民の祝日（祝日法、2020年以降の規則）。通知の「土日祝」の判定に使う純粋関数。

外部ライブラリを使わない（docs/08）。春分・秋分は 1980〜2099 年に使える近似式。
法改正で祝日が変わったら、ここを直してテスト（tests/test_holidays.py）を足す。
"""
from __future__ import annotations

from datetime import date, timedelta


def _nth_monday(year: int, month: int, n: int) -> date:
    first = date(year, month, 1)
    offset = (0 - first.weekday()) % 7  # 月曜=0
    return first + timedelta(days=offset + 7 * (n - 1))


def _equinox(year: int, base: float) -> int:
    y = year - 1980
    return int(base + 0.242194 * y - y // 4)


def _national(year: int) -> set[date]:
    return {
        date(year, 1, 1),  # 元日
        _nth_monday(year, 1, 2),  # 成人の日
        date(year, 2, 11),  # 建国記念の日
        date(year, 2, 23),  # 天皇誕生日
        date(year, 3, _equinox(year, 20.8431)),  # 春分の日
        date(year, 4, 29),  # 昭和の日
        date(year, 5, 3), date(year, 5, 4), date(year, 5, 5),  # 憲法記念日・みどりの日・こどもの日
        _nth_monday(year, 7, 3),  # 海の日
        date(year, 8, 11),  # 山の日
        _nth_monday(year, 9, 3),  # 敬老の日
        date(year, 9, _equinox(year, 23.2488)),  # 秋分の日
        _nth_monday(year, 10, 2),  # スポーツの日
        date(year, 11, 3),  # 文化の日
        date(year, 11, 23),  # 勤労感謝の日
    }


def holidays_of_year(year: int) -> list[date]:
    """その年の祝日・国民の休日・振替休日（昇順）。"""
    base = _national(year)
    days = set(base)
    # 国民の休日：前日と翌日が祝日で、それ自体は祝日でない日
    for d in base:
        mid = d + timedelta(days=1)
        if mid not in base and mid + timedelta(days=1) in base and mid.year == year:
            days.add(mid)
    # 振替休日：祝日が日曜なら、その後の最初の祝日でない日
    for d in sorted(base):
        if d.weekday() == 6:
            sub = d + timedelta(days=1)
            while sub in days:
                sub += timedelta(days=1)
            if sub.year == year:
                days.add(sub)
    return sorted(days)


def holidays_between(start: date, end: date) -> list[date]:
    """start〜end（両端を含む）の祝日。"""
    out: list[date] = []
    for y in range(start.year, end.year + 1):
        out.extend(d for d in holidays_of_year(y) if start <= d <= end)
    return out
