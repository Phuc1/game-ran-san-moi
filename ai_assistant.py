import os
from openai import OpenAI

api_key = os.environ.get("NVIDIA_API_KEY")
if not api_key:
    raise RuntimeError("Missing NVIDIA_API_KEY environment variable")

client = OpenAI(
    base_url="https://integrate.api.nvidia.com/v1",
    api_key=api_key,
)

completion = client.chat.completions.create(
    model="nvidia/nemotron-3.5-super-vl-120b-a12b",
    messages=[
        {
            "role": "system",
            "content": (
                "You are a senior Android developer. "
                "Analyze the provided source code, identify bugs, "
                "and propose safe, testable fixes."
            ),
        },
        {
            "role": "user",
            "content": (
                "Help me review the game-ran-san-moi Android project. "
                "Start by identifying the project structure and build issues."
            ),
        },
    ],
    temperature=0.2,
    max_tokens=4096,
)

print(completion.choices[0].message.content or "")
