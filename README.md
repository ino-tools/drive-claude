# ドライブClaude

運転中にClaude（または Gemini）とハンズフリーで音声会話するAndroidアプリ。

- 「開始」を押すと「聞く → Claudeに聞く → 読み上げる → また聞く」を自動で繰り返す
- 前面サービスで動くので、ナビなど他のアプリを表示中も会話が続く
- 「ストップ」で終了、「リセット」で会話を最初から
- Web検索対応（天気・ニュースなど）

## 使い方
1. [Releases](../../releases/latest) から `drive-claude.apk` をダウンロードしてインストール
2. 設定に Anthropic の APIキー（console.anthropic.com → API Keys）を入力
3. 「開始」を押して話しかける

APIキーは端末内にのみ保存され、コードには含まれません。
