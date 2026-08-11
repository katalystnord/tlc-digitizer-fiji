# Validation data — provenance and licensing

This directory holds the fixtures and recorded outputs behind the benchmark
reported in the top-level README. Two different sources are mixed here, with
different licensing, so they are documented separately.

## `tlcyzer-paper/` — third-party reference plates

The three reference plate photographs (MOESM2, MOESM3, MOESM4) are
supplementary material from:

> Hauk C, Boss M, Gabel J, et al. "An open-source smartphone app for the
> quantitative evaluation of thin-layer chromatographic analyses in medicine
> quality screening." *Scientific Reports* **12**, 13433 (2022).
> https://doi.org/10.1038/s41598-022-17527-y

That article is open access under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

**The source images themselves are not committed** — they are excluded by
`.gitignore` and must be downloaded from the article's supplementary material
to run `ValidationTest`:

```sh
./mvnw test -Dtest=ValidationTest -Dvalidation.data.dir=/path/to/tlcyzer-paper
```

What *is* committed:

- `tlcyzer-moesm*.json` — our own analysis fixtures (corner coordinates,
  background method, threshold, reference concentrations). Original work,
  GPL-3.0 with the rest of this repository.
- `moesm*-labkit-digitized.csv` / `.png` — recorded outputs of interactive
  Labkit training sessions. The PNGs are annotated overlays rendered on top of
  the CC BY 4.0 source plate images and are therefore **derivative works of
  Hauk et al. 2022, reused here under CC BY 4.0 with attribution as given
  above**. They are kept because Labkit training is not fully deterministic,
  so these particular results cannot be regenerated exactly — they are the
  primary evidence for the recovery/RSD figures discussed in the README.

## `img_00451/` — our own plate photograph

`img_00451-e1488277851441.jpg` is an original photograph taken for this
project (a UV254 F254 plate, six lanes, photographed off-axis without a
light box). It is our own work, licensed GPL-3.0 with the rest of this
repository, and is committed directly because it serves as the real-photo
regression fixture for `Img00451DetectionRegressionTest`.

The accompanying `-digitized.csv` / `-digitized.png` are this project's own
analysis outputs for that photograph.
