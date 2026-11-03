# P25 voice: sources and licenses

- `P25Voice.kt` takes a P25 Phase 1 voice frame (LDU1 / LDU2) apart into its nine IMBE codewords and corrects them (Golay 23,12, Hamming 15,11, the PN descrambling, the hex-word algorithm ID), with the frame layout and interleave tables of DSD.
- `ImbeDecoder.kt` is the IMBE 4400 vocoder: a Kotlin port of mbelib's IMBE parameter decoding, spectral enhancement and speech synthesis, with its tables (`B2`, `BA`, `HOBA`, `BO_M` / `BO_B`, generated from `imbe7200x4400_const.h`; the synthesis window and block lengths computed).

| Part | Origin | License |
|---|---|---|
| LDU layout, IMBE interleave tables, Golay / PN decoding | [DSD](https://github.com/szechyjs/dsd) (Copyright (C) 2010 DSD Author) and [mbelib](https://github.com/szechyjs/mbelib) | ISC |
| IMBE 7200x4400 parameter decoding, spectral amplitude enhancement, speech synthesis and tables | [mbelib](https://github.com/szechyjs/mbelib) (Copyright (C) 2010 mbelib Author) | ISC |

The ISC license of DSD and mbelib:

> Permission to use, copy, modify, and/or distribute this software for any
> purpose with or without fee is hereby granted, provided that the above
> copyright notice and this permission notice appear in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND ISC DISCLAIMS ALL WARRANTIES WITH
> REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
> AND FITNESS. IN NO EVENT SHALL ISC BE LIABLE FOR ANY SPECIAL, DIRECT,
> INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
> LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE
> OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
> PERFORMANCE OF THIS SOFTWARE.

IMBE is a voice coding method of Digital Voice Systems, Inc. (DVSI); the P25 standard (TIA-102.BABA) describes it.
