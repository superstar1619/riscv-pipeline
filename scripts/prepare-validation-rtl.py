#!/usr/bin/env python3
"""Derive a validation-only RTL build that reads its caller's immutable ROM."""
import argparse
from pathlib import Path
import re


def prepare(source, destination):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve():
        raise ValueError('Derived validation RTL must not replace the production source')
    original = source.read_text()
    derived, count = re.subn(r'(?m)^(\s*\$readmemh\(\s*)"[^"]+"(\s*,)',
                             r'\g<1>"program.memfile"\g<2>', original)
    if count != 1:
        raise ValueError(f'Expected exactly one instruction ROM readmemh, found {count}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(derived)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination)
