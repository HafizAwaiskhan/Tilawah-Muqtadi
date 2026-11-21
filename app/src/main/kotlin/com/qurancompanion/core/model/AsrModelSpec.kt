package com.qurancompanion.core.model

/** One file of a downloadable model, pinned by URL, exact size and SHA-256. */
data class ModelFileSpec(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * A versioned on-device model. It is installed into `filesDir/models/<dirName>/`. The directory
 * name carries the version, and a `VERSION` file in it records [versionTag] + the file hashes, so
 * an install from another spec (old app version, partial copy) is detected as a mismatch.
 */
data class AsrModelSpec(
    val id: String,
    val version: String,
    val dirName: String,
    val files: List<ModelFileSpec>,
    val licenseName: String,
    val attribution: String,
    val licenseText: String,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val versionTag: String get() = "$id/$version\n" + files.joinToString("\n") { "${it.name} ${it.sizeBytes} ${it.sha256}" } + "\n"

    companion object {
        /** Hugging Face revision pinned in docs/MODEL_EVALUATION.md §3. */
        const val ABDOU_REVISION = "0c2744b9d70aaf45ba574c30c5d318c575258e66"
        private const val ABDOU_BASE = "https://huggingface.co/Abdou/quran-asr-small/resolve/$ABDOU_REVISION"

        /**
         * Abdou/quran-asr-small INT8 (MIT). Sizes and hashes verified against the files in models/
         * (docs/MODEL_EVALUATION.md §3.1; the model hash is the HF LFS oid).
         */
        val ABDOU_V1 = AsrModelSpec(
            id = "abdou-quran-asr-small",
            version = "int8-${ABDOU_REVISION.take(7)}",
            dirName = "abdou-v1",
            files = listOf(
                ModelFileSpec(
                    "model.int8.onnx", "$ABDOU_BASE/model.int8.onnx", 36_081_762L,
                    "88f9df290d57b8890275d79187b77515aeeb12626a42bc76cbd0f8bfcaff00f0",
                ),
                ModelFileSpec(
                    "tokens.txt", "$ABDOU_BASE/tokens.txt", 180L,
                    "75b53814499cee84a07c7b7a318524d30bcaf84273c0f8b28a04176023e8c74f",
                ),
            ),
            licenseName = "MIT",
            attribution = "Speech recognition model: quran-asr-small by Abdou " +
                "(huggingface.co/Abdou/quran-asr-small), MIT License. Training data keeps its own terms " +
                "(tarteel-ai/tlog: CC BY 4.0).",
            licenseText = MIT_ABDOU,
        )
    }
}

private const val MIT_ABDOU = """MIT License

Copyright (c) 2026 Abdou

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""
