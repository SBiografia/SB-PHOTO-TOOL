"""Generate synthetic TIFF fixtures with opaque and library-unsupported tags; no user photos."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1] / 'app/src/test/resources/metadata'


def fixture(endian, width, height, offset=True):
    def pack(fmt, *args):
        return struct.pack(endian + fmt, *args)

    def ascii_value(text):
        return text.encode('ascii') + b'\0'

    def rational(*pairs):
        return b''.join(pack('II', *p) for p in pairs)

    primary = [
        (256, 4, 1, pack('I', width)), (257, 4, 1, pack('I', height)),
        (271, 2, 10, ascii_value('Synthetic')), (272, 2, 11, ascii_value('TestCamera')),
        (274, 3, 1, pack('H', 6)), (305, 2, 9, ascii_value('Original')),
        (306, 2, 20, ascii_value('2026:11:01 03:04:05')),
        (315, 2, 7, ascii_value('Artist')), (33432, 2, 10, ascii_value('Copyright')),
        (34665, 4, 1, None), (34853, 4, 1, None),
        (0xC7A1, 7, 12, b'\x00\xffUNKNOWN\x00\x80\x01'),
    ]
    comment = b'UNICODE\0' + '합성 설명'.encode('utf-16-be')
    maker = b'UNREGISTERED\x00\xff\x01\x80MAKERNOTE\x00\x11'
    exif = [
        (33434, 5, 1, rational((1, 125))), (33437, 5, 1, rational((18, 10))),
        (34855, 3, 1, pack('H', 100)),
        (36867, 2, 20, ascii_value('2026:11:01 03:04:05')),
        (36868, 2, 20, ascii_value('2026:11:01 03:04:06')),
        (37386, 5, 1, rational((43, 10))),
        (37500, 7, len(maker), maker), (37510, 7, len(comment), comment),
        (37521, 2, 7, ascii_value('123456')),
        (40962, 4, 1, pack('I', width)), (40963, 4, 1, pack('I', height)),
        (42035, 2, 9, ascii_value('LensMake')), (42036, 2, 10, ascii_value('LensModel')),
        (42037, 2, 11, ascii_value('SERIAL1234')),
    ]
    if offset:
        exif += [(36880, 2, 7, ascii_value('+09:00')), (36881, 2, 7, ascii_value('+09:00')), (36882, 2, 7, ascii_value('+09:00'))]
    gps = [
        (0, 1, 4, bytes([2, 3, 0, 0])), (1, 2, 2, b'N\0'),
        (2, 5, 3, rational((37, 1), (30, 1), (12345, 1000))), (3, 2, 2, b'E\0'),
        (4, 5, 3, rational((127, 1), (10, 1), (98765, 1000))),
        (5, 1, 1, b'\0'), (6, 5, 1, rational((12345, 100))),
        (7, 5, 3, rational((18, 1), (4, 1), (5123456, 1000000))),
        (8, 2, 7, ascii_value('01,02,')), (9, 2, 2, b'A\0'), (10, 2, 2, b'3\0'),
        (11, 5, 1, rational((123, 100))), (12, 2, 2, b'K\0'),
        (13, 5, 1, rational((345, 10))), (14, 2, 2, b'T\0'),
        (15, 5, 1, rational((1234, 10))), (16, 2, 2, b'T\0'),
        (17, 5, 1, rational((4567, 100))), (18, 2, 7, ascii_value('WGS-84')),
        (19, 2, 2, b'N\0'), (20, 5, 3, rational((37, 1), (31, 1), (12, 1))),
        (21, 2, 2, b'E\0'), (22, 5, 3, rational((127, 1), (11, 1), (34, 1))),
        (23, 2, 2, b'T\0'), (24, 5, 1, rational((123, 1))),
        (25, 2, 2, b'K\0'), (26, 5, 1, rational((12345, 100))),
        (27, 7, 12, b'ASCII\0\0\0GPS\0'), (28, 7, 13, b'ASCII\0\0\0AREA\0'),
        (29, 2, 11, ascii_value('2026:10:31')), (30, 3, 1, pack('H', 1)),
        (31, 5, 1, rational((123, 100))),
    ]
    groups = [sorted(primary), sorted(exif), sorted(gps)]
    starts = [8]
    for group in groups[:-1]:
        starts.append(starts[-1] + 2 + len(group) * 12 + 4)
    data_start = starts[-1] + 2 + len(groups[-1]) * 12 + 4
    directories, values = bytearray(), bytearray()
    for group in groups:
        directories += pack('H', len(group))
        for tag, kind, count, value in group:
            if tag in (34665, 34853):
                value = pack('I', starts[1 if tag == 34665 else 2])
            expected_size = count * {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1}[kind]
            assert len(value) == expected_size, (tag, len(value), expected_size)
            if len(value) > 4:
                pointer = data_start + len(values)
                entry = pack('I', pointer)
                values += value
                if len(values) % 2:
                    values += b'\0'
            else:
                entry = value.ljust(4, b'\0')
            directories += pack('HHI', tag, kind, count) + entry
        directories += pack('I', 0)
    return (b'II' if endian == '<' else b'MM') + pack('HI', 42, 8) + directories + values


if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    for endian, suffix in [('<', 'le'), ('>', 'be')]:
        for width, height, label in [(240, 400, 'original'), (120, 200, 'resized')]:
            (ROOT / f'{label}-{suffix}.tiff').write_bytes(fixture(endian, width, height))
    (ROOT / 'unzoneddatetime.tiff').write_bytes(fixture('<', 240, 400, False))
    print('Generated five synthetic metadata fixtures.')
