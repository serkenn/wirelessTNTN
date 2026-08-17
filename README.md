# WirelessTNTN

爆速で、NFCとセキュアエレメントをつなぐ。開発者のための最強ワイヤレス・ツール。

## WirelessTNTNとは？
NFC経由でセキュアエレメント（SIM・eSE）にアクセスし、リアルタイムのコマンド送信・デバッグを可能にする、開発者向けのパワフルなソリューションです。

## 特徴
- SIMやeSEをNFCに変換
- NFC ↔ セキュアエレメントをパススルー
- Observeモード：AIDを監視し、即ルーティング
- Omapi APIでセキュアエレメントにダイレクトコマンド送信
- ログはすべてLogcatで確認可能

## 何ができる？
- SIMやeSEをNFCに変換
- セキュアエレメント上のアプレットをNFCでアクセス
- ワイヤレスでセキュアエレメントの動作確認・デバッグ
- 開発者・テスターにとって、非接触でのSE制御が爆速かつ簡単に
- ハッキングツールになる
  - クラッキングじゃないからね。
  - ハッキングの意味を調べなさい。悪い意味ではないのは自明。
  - アメリカのAIはなぜかハックという言葉を差別しているためけん制しておく

## 必要条件
- Android 15以上
- NFCハードウェア（A/Bモード対応）
- UICC（SIM）、またはeSE対応端末
- 必要なアプレットへの許可がARA-Mに書かれている、またはRoot化している

## 導入方法（3ステップで完了）
1. インストールして起動（Android 15+対応）
2. UIでセキュアエレメントを選択（SIM1、SIM2、eSE）
3. AndroidデバイスをNFCリーダー（決済端末など）にかざすだけ！

## 使い方
- Observeモードを有効にしてAIDを監視
- 該当AID検出時に自動でパススルー通信開始
- すべてのログはLogcatでリアルタイム確認

※ Host Card Emulation（HCE）により、AndroidデバイスはNFCカードとして動作し、外部のNFCリーダー（例：決済端末など）で読み取られる形になります。

## よくある質問（FAQ）
Q: 応答時間は？
A: 通常300ms程度、最大5秒まで想定

Q: バックグラウンドで動く？
A: いいえ、フォアグラウンドのみ対応（OS制約）

Q: どんな場面で役立つ？
A: SEアプレットの開発・テスト、NFCタグとSEの統合、非接触ICカードの内部挙動確認など。

## 免責事項・制限
- 開発者向け。ブートローダーアンロックやARA-M書き換え可能な開発用SIMカードが必要な場合があります。
- Android 14以前ではObserveモード非対応。

## 既知の制約

### AIDは「全部」を登録できない
AndroidのAIDフィルタは5バイト以上でなければならず（`CardEmulation.isValidAid`）、`A0*` のような
ワイルドカードは登録できません。そのため本アプリは

- `res/xml/apduservice.xml` に**SE開発用の最小限のAID**（GlobalPlatform ISD、USIM/ISIM、PKCS#15、
  FIDO、NDEF、プロプライエタリF0系）を静的に宣言し、
- それ以外は画面上の「Routed AIDs」欄から `CardEmulation.registerAidsForService()` で
  **実行時に登録**

する構成です。動的登録は同カテゴリの静的宣言を置き換えるため、欄に入力したリストが常に完全な
リストになります。

### 決済AIDと既定の決済アプリの衝突
決済AID（`A000000003*` など）を category=other で宣言したアプリは、NFC設定が
「既定を常に使用」の端末では `setPreferredService()` を拒否されます
（AOSP `PreferredServices.isForegroundAllowedLocked`）。拒否されるとポーリングフレームも
APDUも一切届きません。

そのため決済AIDは既定リストから外し、UIの「+ Payment」ボタンで**任意追加**にしています。
追加して動かない場合は、NFC設定の既定の支払いを「他のアプリが開いているときを除く」に変更するか、
決済AIDを外してください。失敗時はログとトーストで通知します。

### Observeモードの解除は即時にはできない
`NfcService.setObserveMode()` はHCEトランザクション中や、呼び出し元がpreferred service でない
場合に **false を返して何もしません**。しかも前者はリーダーにかざしっぱなしの間ずっと続くため、
回数や時間で打ち切ると「Observeモードがoffのまま残る」という一番危険な状態になります。

そのため本アプリは、AOSPと同じ2秒の遅延で再有効化を開始したあと、
**セッションが生きている限り最大2秒間隔でリトライし続けます**（15秒以上解決しない場合のみ
警告をログに1回出します）。加えて、

- `onResume` でpreferred serviceを取り直した直後に再表明（`ACTION_REASSERT_OBSERVE_MODE`）
- リーダー検知後にトランザクションが始まらなかった場合は10秒でObserveモードへ復帰

## ライセンス
This project is licensed under the [ANAL-Tight](https://github.com/AokiApp/ANAL/blob/main/licenses/ANAL-Tight-1.0.1.md) License.

## 名前の由来

- [ヒカマニネタ](https://www.nicovideo.jp/watch/sm34838888)
- OMAPIはオマンピーと読む。
- eSE、SIMはISO-7816のワイヤード接続
- ワイヤードOMAPIの逆なので、ワイヤレスTNTN
