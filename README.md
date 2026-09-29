# On-Device LLM Chat (Android)

[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) を使ってオンデバイスLLMとチャットするだけの最小アプリ。

## モデルの用意

`.litertlm` 形式のモデルを [litert-community](https://huggingface.co/litert-community) から取得する。

| モデル | サイズ | 備考 |
|---|---|---|
| [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) `gemma-4-E2B-it.litertlm` | 2.6 GB | おすすめ。日本語も自然。GPU / CPU 両対応 |
| [Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) `Qwen3-0.6B.litertlm` | 0.6 GB | 動作確認用。軽いが日本語は怪しい |

同じリポジトリにある `gemma-4-E2B-it-gpu.litertlm`（2.0 GB）は使わないこと。日本語で「はい、承知」の直後に `�` や「 keto」のような無関係なトークンを出し、そのあと文を言い直すことを確認している（Mac の GPU で seed 0〜4 のすべて）。

## ビルドと実行

```sh
./gradlew :app:installDebug
adb shell mkdir -p /sdcard/Android/data/com.example.ondevicellm/files
adb push Qwen3-0.6B.litertlm /sdcard/Android/data/com.example.ondevicellm/files/
adb shell am start -n com.example.ondevicellm/.MainActivity
```

JDK は Android Studio 同梱のものを使う：
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

- 前回使ったモデル（初回はモデルが 1 つだけならそれ）を起動時に自動で読み込む。切り替えはメニューの「モデルを選択」から
- システムプロンプトはメニューから編集できる（デフォルト: 「あなたは親切なアシスタントです。日本語で答えてください。」）
- Qwen3 は発話末尾に `/no_think` を自動付与して思考モードを止める。`<think>…</think>` は表示から除く
- メニューの「ファイルから取り込む」を使うと、端末内のファイル（Downloads など）をアプリ領域にコピーして使える
- バックエンドは GPU / CPU を切り替えられ、選択は次回起動時も引き継ぐ。GPU の初期化に失敗した場合は CPU にフォールバックし、そのモデルは次回から最初から CPU で開く（メニューで GPU を選び直すと再挑戦する）
- モデルの読み込み中も入力・送信できる。送った発話は読み込みが終わった時点で応答を始める（停止ボタンで取り消せる）
- 読み込んだモデルはプロセスが生きている間は保持するので、戻るボタンで閉じてから開き直しても読み込み直さない
- 応答の下に、最初のトークンが出るまでの時間とストリーミング速度が表示される
