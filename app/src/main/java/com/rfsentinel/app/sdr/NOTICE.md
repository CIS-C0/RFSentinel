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

# Pager messages: sources and licenses

- `Flex.kt` is a Kotlin port of multimon-ng's FLEX decoder: symbol recovery, frame state machine, phase, address and message parsing.
- `Pocsag.kt` follows multimon-ng's POCSAG decoder and its BCH(31,21) error correction, rewritten for one table shared with FLEX.

| Part | Origin | License |
|---|---|---|
| FLEX decoder (`demod_flex.c`) | [multimon-ng](https://github.com/EliasOenal/multimon-ng), adapted from GNU Radio: Copyright 2004, 2006, 2010 Free Software Foundation, Inc.; (C) 2015 Craig Shelley; (C) 2024 Jason Lingohr | GPL-3.0-or-later |
| POCSAG decoder (`pocsag.c`) | [multimon-ng](https://github.com/EliasOenal/multimon-ng): (C) 1996 Thomas Sailer; (C) 2012-2014 Elias Oenal and others | GPL-2.0-or-later |
| BCH(31,21) correction (`bch.c`) | [multimon-ng](https://github.com/EliasOenal/multimon-ng) | Public domain (Unlicense) |

# SDR driver and receiver: other sources

| Part | Origin | License |
|---|---|---|
| Automatic frequency correction (`FreqCorrection.kt`): correct the tuner by the error a decoder measures on a channel of known frequency | Idea from [SDRTrunk](https://github.com/DSheirer/sdrtrunk) `TunerFrequencyErrorManager` (C) 2014-2026 Dennis Sheirer; written anew | GPL-3.0-or-later |
| Bias-tee on GPIO 0 (`RtlSdr.setBiasTee`) | [rtl-sdr-blog](https://github.com/rtlsdrblog/rtl-sdr-blog) librtlsdr `rtlsdr_set_bias_tee` | GPL-2.0-or-later |
| ADS-B demodulator at 2.4 MS/s (`ModeS.Demod`): preamble match at five sample phases, phase-aware bit slicing, best-scoring phase kept | Port of [dump1090-fa](https://github.com/flightaware/dump1090) `demod_2400.c`, Copyright (c) 2014, 2015 Oliver Jowett | GPL-2.0-or-later |
| ADS-B automatic gain (`AdsbGain.kt`): dynamic-range mode, gain steered by the noise floor's 40th percentile | Port of [dump1090-fa](https://github.com/flightaware/dump1090) `adaptive.c`, Copyright (c) 2021 FlightAware, LLC | GPL-2.0-or-later |
| Listening from the waterfall (`AudioDemod.kt`): channel mix and filter, FM as the phase step between samples, AM as the envelope | Follows [RFAnalyzer](https://github.com/demantz/RFAnalyzer) `Demodulator.kt`, Copyright (C) 2025 Dennis Mantz; written anew | GPL-2.0-or-later |
