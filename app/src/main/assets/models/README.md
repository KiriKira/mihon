# Page orientation model

`page_orientation.onnx` is converted from PaddleOCR's
`PP-LCNet_x1_0_doc_ori` inference model. It classifies document images into
0°, 90°, 180°, and 270° orientations.

- Source: https://github.com/PaddlePaddle/PaddleOCR
- Model documentation: https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/doc_img_orientation_classification.en.md
- License: Apache License 2.0
- Conversion: Paddle2ONNX, opset 17, with constant folding
- SHA-256: `96e898f047a0e460ba0652e9afb8c874e53872821cfd7a3fec53a5ab62df92f0`
