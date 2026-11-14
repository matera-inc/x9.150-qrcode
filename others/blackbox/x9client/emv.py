# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""EMV merchant-presented QR: TLV decoding and the CRC16 check.

The string is ``ID(2) LEN(2) VALUE(LEN)`` repeated. Templates (26..51 merchant account
information, 62 additional data, 64 language template, 80..99 unreserved) hold TLVs themselves,
and are decoded one level down. The last field is ``6304`` and a CRC16/CCITT-FALSE over
everything before its value, the ``6304`` included.
"""

TEMPLATE_TAGS = {f"{n:02d}" for n in list(range(26, 52)) + [62, 64] + list(range(80, 100))}


class EmvError(ValueError):
    """The string is not well-formed EMV TLV."""


def crc16(data):
    """CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF), as EMVCo specifies, upper-case hex."""
    if isinstance(data, str):
        data = data.encode()
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return f"{crc:04X}"


def crc_is_valid(emv):
    """Whether the trailing CRC matches. A wrong one makes every scanner reject the code."""
    return len(emv) > 8 and emv[-8:-4] == "6304" and emv[-4:].upper() == crc16(emv[:-4])


def parse(emv):
    """The top-level fields, in order, as a list of ``(tag, value)``.

    Raises :class:`EmvError` on a truncated or non-numeric length rather than guessing.
    """
    fields, i = [], 0
    while i < len(emv):
        if i + 4 > len(emv):
            raise EmvError(f"truncated field header at {i}: {emv[i:]!r}")
        tag, length = emv[i:i + 2], emv[i + 2:i + 4]
        if not length.isdigit():
            raise EmvError(f"length of tag {tag} at {i} is not numeric: {length!r}")
        n = int(length)
        value = emv[i + 4:i + 4 + n]
        if len(value) != n:
            raise EmvError(f"tag {tag} at {i} declares {n} characters, has {len(value)}")
        fields.append((tag, value))
        i += 4 + n
    return fields


def decode(emv):
    """A dict of tag to value; template tags decode to a dict of their own sub-tags.

    A tag that repeats keeps its first value (EMV does not repeat top-level tags).
    """
    out = {}
    for tag, value in parse(emv):
        if tag in out:
            continue
        if tag in TEMPLATE_TAGS:
            try:
                out[tag] = dict(parse(value))
            except EmvError:
                out[tag] = value
        else:
            out[tag] = value
    return out


def template_with(emv, gui):
    """The (tag, sub-fields) of the template whose sub-tag 00 is ``gui`` (e.g. ``org.x9``)."""
    for tag, value in decode(emv).items():
        if isinstance(value, dict) and value.get("00") == gui:
            return tag, value
    return None, None
