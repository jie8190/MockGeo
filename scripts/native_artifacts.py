"""Validate that stripping changes debug metadata, never loaded code or dynamic symbols."""
from pathlib import Path
import struct


def elf_runtime(path):
    data = Path(path).read_bytes()
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected little-endian ELF64')
    section_offset = struct.unpack_from('<Q', data, 40)[0]
    section_size, section_count, string_index = struct.unpack_from('<HHH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, section_offset + n * section_size)
                for n in range(section_count)]
    strings = sections[string_index]
    names = data[strings[4]:strings[4] + strings[5]]
    allocated = {}
    debug = []
    for section in sections:
        name = names[section[0]:].split(b'\0', 1)[0].decode()
        if name.startswith('.debug'):
            debug.append(name)
        if section[2] & 2:  # SHF_ALLOC, including .text/.dynsym/.dynamic/unwind tables
            content = b'' if section[1] == 8 else data[section[4]:section[4] + section[5]]
            allocated[name] = (section[1], section[2], section[3], section[5], content)
    program_offset = struct.unpack_from('<Q', data, 32)[0]
    program_size, program_count = struct.unpack_from('<HH', data, 54)
    programs = data[program_offset:program_offset + program_size * program_count]
    return allocated, programs, debug


def verify_stripped(original, stripped):
    before, before_programs, _ = elf_runtime(original)
    after, after_programs, debug = elf_runtime(stripped)
    if before != after or before_programs != after_programs or debug:
        raise ValueError('Stripped ELF changed runtime sections/segments or retained debug data')
