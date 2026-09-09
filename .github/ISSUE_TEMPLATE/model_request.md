---
name: Model Support Request
about: Propose support for a new ONNX / NPU diffusion or upscaling model
title: '[MODEL] '
labels: ['model-support']
assignees: ''
---

**Model Name & Link**
- Name: [e.g. FLUX.1-schnell, SDXL Turbo, LCM-LoRA preset]
- Hugging Face / Model Hub URL: [https://huggingface.co/...]

**Model Architecture Details**
- Framework: [ONNX / Olive / QNN Context]
- Target Precision: [INT8 / FP16]
- UNet / Transformer Size: [e.g. ~800 MB INT8]
- Text Encoder: [CLIP / T5]
- Memory Estimate: [e.g. < 2.5 GB peak RAM]

**Inference Feasibility on Hexagon HTP / Mobile NPU**
- Number of required inference steps: [e.g. 1–4 steps for Turbo/LCM, 20 steps for standard]
- Can the model be quantized to INT8/FP16 without severe quality degradation?
- License of the weights: [e.g. OpenRAIL, Apache 2.0, Non-commercial]

**Additional Context**
Add any test scripts, benchmark logs, or conversion recipes.
