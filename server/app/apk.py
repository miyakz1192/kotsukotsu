"""APK の中身の読み取り（管理画面でのアップロード時に使う）。

- AndroidManifest.xml（バイナリXML）から パッケージ名・versionCode・versionName・debuggable・minSdk
- APK Signature Scheme v2/v3 の署名ブロックから、署名証明書の SHA-256
外部ツール（aapt など）に頼らず、必要な部分だけを読む。署名の正しさの検証は端末（Android）が行う。
"""
from __future__ import annotations

import hashlib
import io
import struct
import zipfile
from dataclasses import dataclass


class ApkError(ValueError):
    pass


@dataclass(frozen=True)
class ApkInfo:
    package_name: str
    version_code: int
    version_name: str | None
    debuggable: bool
    min_sdk: int | None
    cert_sha256: str | None  # 署名証明書（DER）の SHA-256（16進小文字）。署名がなければ None
    sha256: str  # APK ファイル全体の SHA-256
    size: int


# android 名前空間の属性のリソースID（名前が削られていても読めるように ID で判定する）
_ATTR_IDS = {
    0x0101021B: "versionCode",
    0x0101021C: "versionName",
    0x0101000F: "debuggable",
    0x0101020C: "minSdkVersion",
}

_TYPE_STRING = 0x03
_TYPE_INT_DEC = 0x10
_TYPE_INT_HEX = 0x11
_TYPE_BOOLEAN = 0x12


def _read_string_pool(buf: bytes, start: int) -> list[str]:
    _, header_size, _, count, _, flags, strings_start, _ = struct.unpack_from("<HHIIIIII", buf, start)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", buf, start + header_size)
    base = start + strings_start
    out = []
    for off in offsets:
        p = base + off
        if utf8:
            # UTF-16 での長さ（読み飛ばす）→ UTF-8 のバイト長
            n = buf[p]; p += 1
            if n & 0x80:
                p += 1
            n = buf[p]; p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p]; p += 1
            out.append(buf[p:p + n].decode("utf-8", errors="replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]; p += 2
            out.append(buf[p:p + n * 2].decode("utf-16-le", errors="replace"))
    return out


def parse_manifest(buf: bytes) -> dict:
    """バイナリXMLの manifest / application / uses-sdk 要素から必要な属性を取り出す。"""
    if len(buf) < 8 or struct.unpack_from("<H", buf, 0)[0] != 0x0003:
        raise ApkError("AndroidManifest.xml がバイナリXMLではありません")
    strings: list[str] = []
    res_ids: list[int] = []
    result: dict = {}
    pos = struct.unpack_from("<H", buf, 2)[0]
    while pos + 8 <= len(buf):
        ctype, hsize, csize = struct.unpack_from("<HHI", buf, pos)
        if csize < 8:
            raise ApkError("AndroidManifest.xml が壊れています")
        if ctype == 0x0001:
            strings = _read_string_pool(buf, pos)
        elif ctype == 0x0180:
            res_ids = list(struct.unpack_from(f"<{(csize - hsize) // 4}I", buf, pos + hsize))
        elif ctype == 0x0102:
            ext = pos + hsize
            _, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", buf, ext)
            tag = strings[name_idx] if name_idx < len(strings) else ""
            if tag in ("manifest", "application", "uses-sdk"):
                for i in range(attr_count):
                    a = ext + attr_start + i * attr_size
                    _, aname, raw, _, _, dtype, data = struct.unpack_from("<IIIHBBI", buf, a)
                    key = _ATTR_IDS.get(res_ids[aname]) if aname < len(res_ids) else None
                    if key is None:
                        key = strings[aname] if aname < len(strings) else ""
                    if dtype == _TYPE_STRING:
                        val = strings[data]
                    elif dtype in (_TYPE_INT_DEC, _TYPE_INT_HEX):
                        val = data
                    elif dtype == _TYPE_BOOLEAN:
                        val = data != 0
                    elif raw != 0xFFFFFFFF and raw < len(strings):
                        val = strings[raw]
                    else:
                        val = data
                    result[(tag, key)] = val
        pos += csize
    return result


_SIG_MAGIC = b"APK Sig Block 42"
_V2_ID = 0x7109871A
_V3_ID = 0xF05368C0


def _lp(buf: bytes, pos: int) -> tuple[bytes, int]:
    n = struct.unpack_from("<I", buf, pos)[0]
    return buf[pos + 4:pos + 4 + n], pos + 4 + n


def signer_cert_sha256(data: bytes) -> str | None:
    """APK 署名ブロック（v3 を優先、なければ v2）の最初の署名者の証明書の SHA-256。"""
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0:
        raise ApkError("ZIP の終端が見つかりません")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset < 24 or data[cd_offset - 16:cd_offset] != _SIG_MAGIC:
        return None  # v2 以降の署名なし
    size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - size - 8
    pairs: dict[int, bytes] = {}
    p = start + 8
    while p < cd_offset - 24:
        n, pid = struct.unpack_from("<QI", data, p)
        pairs[pid] = data[p + 12:p + 8 + n]
        p += 8 + n
    block = pairs.get(_V3_ID) or pairs.get(_V2_ID)
    if block is None:
        return None
    signers, _ = _lp(block, 0)
    signer, _ = _lp(signers, 0)
    signed_data, _ = _lp(signer, 0)
    _digests, q = _lp(signed_data, 0)
    certs, _ = _lp(signed_data, q)
    cert, _ = _lp(certs, 0)
    return hashlib.sha256(cert).hexdigest()


def inspect_apk(data: bytes) -> ApkInfo:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            manifest = z.read("AndroidManifest.xml")
    except (zipfile.BadZipFile, KeyError) as e:
        raise ApkError("APKファイルではないようです（AndroidManifest.xml がありません）") from e
    try:
        m = parse_manifest(manifest)
        cert = signer_cert_sha256(data)
    except (struct.error, IndexError) as e:
        raise ApkError("APKの中身を読み取れませんでした") from e
    package = m.get(("manifest", "package"))
    code = m.get(("manifest", "versionCode"))
    if not isinstance(package, str) or not package or not isinstance(code, int):
        raise ApkError("APKのパッケージ名またはバージョン番号を読み取れませんでした")
    name = m.get(("manifest", "versionName"))
    min_sdk = m.get(("uses-sdk", "minSdkVersion"))
    return ApkInfo(
        package_name=package,
        version_code=code,
        version_name=str(name) if name is not None else None,
        debuggable=m.get(("application", "debuggable")) is True,
        min_sdk=min_sdk if isinstance(min_sdk, int) else None,
        cert_sha256=cert,
        sha256=hashlib.sha256(data).hexdigest(),
        size=len(data),
    )
