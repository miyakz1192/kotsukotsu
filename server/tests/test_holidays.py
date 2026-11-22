"""日本の祝日の計算（通知の土日祝判定用、docs/06 1.7）。内閣府の公表値と照合する。"""
from datetime import date

from app.domain.holidays import holidays_between, holidays_of_year


def test_2026_matches_cabinet_office():
    expected = {
        date(2026, 1, 1), date(2026, 1, 12), date(2026, 2, 11), date(2026, 2, 23), date(2026, 3, 20),
        date(2026, 4, 29), date(2026, 5, 3), date(2026, 5, 4), date(2026, 5, 5), date(2026, 5, 6),  # 5/6 振替
        date(2026, 7, 20), date(2026, 8, 11), date(2026, 9, 21), date(2026, 9, 22), date(2026, 9, 23),  # 9/22 国民の休日
        date(2026, 10, 12), date(2026, 11, 3), date(2026, 11, 23),
    }
    assert set(holidays_of_year(2026)) == expected


def test_2027_substitute_and_equinox():
    h = holidays_of_year(2027)
    assert date(2027, 3, 21) in h and date(2027, 3, 22) in h  # 春分が日曜 → 振替
    assert date(2027, 9, 23) in h and date(2027, 9, 20) in h  # 秋分・敬老の日
    assert date(2027, 9, 21) not in h and date(2027, 9, 22) not in h  # 挟まれていない日は休日ではない
    assert date(2027, 1, 11) in h  # 成人の日（第2月曜）


def test_2025_known_days():
    h = holidays_of_year(2025)
    assert date(2025, 2, 24) in h  # 天皇誕生日（日）の振替
    assert date(2025, 5, 6) in h  # こどもの日の翌日は振替（5/4 みどりの日が日曜 → 5/6）
    assert date(2025, 11, 24) in h  # 勤労感謝の日（日）の振替
    assert date(2025, 3, 20) in h and date(2025, 9, 23) in h


def test_between_spans_new_year():
    got = holidays_between(date(2026, 12, 20), date(2027, 1, 20))
    assert got == [date(2027, 1, 1), date(2027, 1, 11)]
