# QloveR Radio Player（Android 15+ / Kotlin）

QloveRの番組をプレイリストにして連続再生するAndroidアプリのソースコード一式です。

## 対応する要件

| 要件 | 実装 |
|---|---|
| Android 15以上 | `minSdk = 35`, `targetSdk = 35`, `compileSdk = 35` |
| 言語 Kotlin | 全ソース Kotlin、UIは Jetpack Compose |
| 音量ノーマライゼーション | `playback/LoudnessNormalizer.kt`。目標LUFS（初期値 -14 LUFS）との差をゲイン補正。小さい曲は `LoudnessEnhancer` で持ち上げ、大きい曲は ExoPlayer の音量で絞る。目標値は画面のスライダーで変更可。曲ごとの実測LUFSと手動ゲイン（-12〜+12dB）は、各曲の「音量調整」または追加時に入力できる |
| 最後に何を再生したか記憶 | Room（プレイリスト/曲）＋ DataStore（最後の曲・位置・設定） |
| 最後再生位置から再開 | 起動時に「前回の続きから再開」カードを表示。サービス側でも30秒ごとに位置を保存 |
| Android Auto対応 | `MediaLibraryService`＋ `automotive_app_desc.xml`（`media`）。車載画面にプレイリスト→曲一覧が出て、選んで再生できる（`QloveRPlayerService` の `onGetChildren` で Room から一覧を返す）。通知・ロック画面・Bluetoothからも操作可 |
| プレイリスト機能で連続再生 | `Player.REPEAT_MODE_ALL`＋ `setMediaItems()`。末尾まで行くと先頭に戻る |
| バックグラウンド再生 | `MediaSessionService`（`foregroundServiceType="mediaPlayback"`）。タスクを消しても再生継続 |
| モバイルデータ節約（追加要件） | 設定画面に2つ追加：① Wi-Fiのときだけ再生する（モバイル回線では開始しない・再生中は一時停止）② 低ビットレートを優先する（上限96kbpsをトラックセレクターに設定） |

## 重要：QloveRの音源について

QloveR（文化放送、https://qlover.jp/ ）には、公式の公開ストリームURLがありません。視聴には公式サイト/公式アプリでの会員登録が必要で、番組ごとに有料会員限定のものもあります。

そのためこのアプリは、**公式の配信URLを勝手に抜き出して再生するものではありません**。次のどちらかで使います。

1. 自分が利用権を持つ配信の音声URL（mp3 / AAC / HLS等）を「曲・配信URLを追加」から登録する
2. 手持ちの音声ファイルを `content://` URI として登録する

`https://qlover.jp/agplayer` はHTMLのプレイヤーページなので、音声URLとして直接は再生できません。アプリ内の「QloveR Streaming をブラウザで開く」ボタンから公式ページを開けます。

## ビルド方法（Android Studio）

1. Android Studio（Ladybug以降）を開き、このフォルダを開く
2. JDK 17、Android SDK 35（Android 15）を入れる
3. Run ▶ で Android 15以上の実機/エミュレータにインストール

コマンドラインの場合（`gradlew` は同梱済み。JDK 17 と Android SDK 35 が必要）：

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> 注意：この環境（MuseのVM）にはAndroid SDKがなく、Gradleデーモン間通信も動かないため、ここではコンパイルしていません。Android Studioでのビルドが必要です。

## 使い方

1. 「新しいプレイリスト名」でプレイリストを作る
2. 「曲・配信URLを追加」で番組名と音声URLを入れる（分かる場合は実測LUFSと手動ゲインも任意で入力できる。後から各曲の「音量調整」でも変更できる）
3. 曲の「再生」を押すと、そのプレイリストを連続再生する
4. 次回起動時に「前回の続きから再開」を押すと、最後の曲・位置から再開する
5. 設定で音量ノーマライゼーションとモバイルデータ節約を切り替える

## 主なファイル

- `app/src/main/java/com/koga/qloverplayer/ui/MainActivity.kt` — Compose画面、プレイリスト作成・追加・再開・設定
- `app/src/main/java/com/koga/qloverplayer/playback/QloveRPlayerService.kt` — MediaLibraryService、バックグラウンド再生、30秒ごとの位置保存、Wi-Fi判定
- `app/src/main/java/com/koga/qloverplayer/playback/LoudnessNormalizer.kt` — ゲイン補正＋LoudnessEnhancer
- `app/src/main/java/com/koga/qloverplayer/data/Models.kt` — Room（Playlist/Track）
- `app/src/main/java/com/koga/qloverplayer/data/PlaybackStateRepository.kt` — DataStore（最後の再生・正規化・データ節約設定）
- `app/src/main/AndroidManifest.xml` — フォアグラウンドサービス、Android Auto宣言
