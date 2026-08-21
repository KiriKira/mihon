# Page orientation model

Implementation status, known limitations, test-corpus conventions, and future
experiments are documented in [`docs/page-orientation.md`](../../../../../docs/page-orientation.md).

`page_orientation.onnx` is converted from PaddleOCR's
`PP-LCNet_x1_0_doc_ori` inference model. It classifies document images into
0°, 90°, 180°, and 270° orientations.

The reader treats this model only as a rotation proposal. It evaluates five
crops at all four quarter-turns and requires their predictions to agree after
alignment. A proposed non-upright result is accepted only when bundled Chinese
or Japanese text recognition also finds sufficient CJK text. Ambiguous pages,
illustrations without text, and upright pages therefore keep their original
orientation. Reader viewers cache each decision and opportunistically prefetch
the next three available pages; foreground requests take priority. Detection
failures are logged and safely fall back to no rotation.

- Source: https://github.com/PaddlePaddle/PaddleOCR
- Model documentation: https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/doc_img_orientation_classification.en.md
- License: Apache License 2.0
- Conversion: Paddle2ONNX, opset 17, with constant folding
- SHA-256: `96e898f047a0e460ba0652e9afb8c874e53872821cfd7a3fec53a5ab62df92f0`
