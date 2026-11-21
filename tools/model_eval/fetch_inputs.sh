#!/usr/bin/env bash
# Download everything run_asr.py needs into models/ (gitignored). Nothing here is committed.
#   tools/model_eval/fetch_inputs.sh
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
M="$REPO/models"

# 1. quran-asr-small (Abdou), pinned revision; the int8 sha256 is the LFS oid reported by the HF API.
REV=0c2744b9d70aaf45ba574c30c5d318c575258e66
SHA_INT8=88f9df290d57b8890275d79187b77515aeeb12626a42bc76cbd0f8bfcaff00f0
D="$M/abdou-quran-asr-small"; mkdir -p "$D"
for f in tokens.txt spelling.json LICENSE README.md; do
  curl -sSfL -o "$D/$f" "https://huggingface.co/Abdou/quran-asr-small/resolve/$REV/$f"
done
if ! echo "$SHA_INT8  $D/model.int8.onnx" | sha256sum -c --quiet 2>/dev/null; then
  # HF redirects LFS files to a CDN host (us.aws.cdn.hf.co) that may be blocked in sandboxes;
  # fall back to a public GitHub mirror and accept it only if the sha256 matches the HF LFS oid.
  curl -sSfL -o "$D/model.int8.onnx" "https://huggingface.co/Abdou/quran-asr-small/resolve/$REV/model.int8.onnx" ||
  curl -sSfL -o "$D/model.int8.onnx" \
    "https://raw.githubusercontent.com/mubi64/mubi64.github.io/HEAD/quranmate/models/quran-asr-small/model.int8.onnx"
  echo "$SHA_INT8  $D/model.int8.onnx" | sha256sum -c
fi

# 2. Reference text: Tanzil Uthmani (CC BY 3.0, verbatim; keep its copyright block).
mkdir -p "$M/reference"
[ -s "$M/reference/tanzil-quran-uthmani.txt" ] ||
  curl -sSfL -o "$M/reference/tanzil-quran-uthmani.txt" \
    "https://tanzil.net/pub/download/index.php?quranType=uthmani&outType=txt-2&agree=true"

# 3. Recitation clips from everyayah.com. All five reciters are NOT in quran-asr-small's training list
#    (Hani_Rifai is one of its declared held-out test reciters).
RECITERS="Hani_Rifai_64kbps mahmoud_ali_al_banna_32kbps Parhizgar_48kbps Khaalid_Abdullaah_al-Qahtaanee_192kbps aziz_alili_128kbps"
AYAHS="001002 002255 018010 036009 055013 067002"
for r in $RECITERS; do
  mkdir -p "$M/audio/everyayah/$r"
  for a in $AYAHS; do
    [ -s "$M/audio/everyayah/$r/$a.mp3" ] ||
      curl -sSfL -o "$M/audio/everyayah/$r/$a.mp3" "https://everyayah.com/data/$r/$a.mp3"
  done
done
echo "inputs ready under $M"
