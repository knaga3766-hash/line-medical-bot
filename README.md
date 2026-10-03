# 服薬リマインダー（Spring Boot + PostgreSQL + LINE公式アカウント）

EclipseへMavenプロジェクトとしてインポートできる、個人利用向けのMVPです。

## 機能

- ログイン保護された、お薬名と複数の服薬時刻の登録・削除
- 予定時刻にLINE公式アカウントから通知
- 今日の予定に「服用した」を一度だけ記録（同じ予定の二重記録はDB制約で防止）
- LINEアカウント連携用の10分有効・一回限りのコード発行
- LINE Webhook署名の検証、ブロック時の連携解除
- Spring SecurityのCSRF保護、パスワードは起動時にBCrypt化

## 必要なもの

- Java 21
- Eclipse IDE for Enterprise Java and Web Developers（Maven / m2e対応）
- PostgreSQL 15以降
- LINE公式アカウントとMessaging APIチャネル

## PostgreSQLの準備

1. PostgreSQLでDBとアプリ専用ユーザーを用意します（パスワードは任意の強い値に変更）。

```sql
CREATE ROLE medication_app LOGIN PASSWORD 'ここを強いパスワードに変更';
CREATE DATABASE medication_app OWNER medication_app ENCODING 'UTF8';
```

2. 通常はテーブルを手動作成しません。アプリ初回起動時にFlywayが `src/main/resources/db/migration/V1__create_medication_tables.sql` を適用します。

手動でスキーマを確認・作成したい場合は `database/setup.sql` を使えます。その場合、起動前に環境変数 `SPRING_FLYWAY_ENABLED=false` を設定してください（Flywayとの二重作成を避けます）。

## Eclipseへの取り込み

1. ZIPを展開します。
2. Eclipseで **File → Import → Maven → Existing Maven Projects** を選びます。
3. 展開した `medication-line-app` フォルダーを選択してFinishします。
4. Java 21をプロジェクトJREに設定し、Maven → Update Projectを実行します。

## 起動前の設定

実行構成のEnvironmentタブへ次を登録してください。秘密値はソースコードやGitへ保存しないでください。

| 環境変数 | 値 |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/medication_app` |
| `DATABASE_USERNAME` | `medication_app` |
| `DATABASE_PASSWORD` | PostgreSQLで設定したパスワード |
| `APP_AUTH_USERNAME` | ログイン名（省略時 `owner`） |
| `APP_AUTH_PASSWORD` | 12文字以上の強いログインパスワード（必須） |
| `APP_TIME_ZONE` | `Asia/Tokyo`（省略時） |
| `LINE_CHANNEL_SECRET` | LINE Developers ConsoleのMessaging APIチャネルシークレット |
| `LINE_CHANNEL_ACCESS_TOKEN` | 同チャネルの長期チャネルアクセストークン |

`MedicationLineAppApplication.java` を **Run As → Java Application** で起動し、`http://localhost:8080` を開きます。

## LINE公式アカウントの設定と連携

1. LINE Official Accountを作成し、LINE Developers ConsoleでMessaging APIチャネルを有効化します。
2. 上記のチャネルシークレットとアクセストークンを環境変数に設定してアプリを再起動します。
3. Webhook URLを `https://公開ホスト名/line/webhook` に設定し、Webhookを有効にしてLINE Developers Consoleから接続確認します。LINE側からアクセスできるHTTPSの公開URLが必要です。
4. アプリにログインし「連携コードを発行」を押します。
5. 専用の公式アカウントを友だち追加し、表示された8文字のコードだけをトークへ送ります。
6. アプリを再読み込みし「LINE連携済み」になったことを確認します。

LINE公式アカウントのMessaging APIは、ユーザーが公式アカウントを友だち追加し、かつブロックしていないことなど、LINE側の配信条件に従います。通知の送達は保証されません。アプリは毎分予定を確認し、予定時刻から10分以内に限ってリマインドを送ります。薬名はLINEメッセージに含めません。

## データ設計

- `medication`: 薬名
- `dose_schedule`: 薬ごとの服薬時刻（同一薬に複数時刻を登録可能）
- `dose_occurrence`: 日付ごとの予定・服用記録・通知記録。一つの時刻につき1日1件の一意制約
- `line_link`: 連携済みLINEユーザー（個人利用のため1件）
- `line_pairing_code`: 連携コードのSHA-256ハッシュと有効期限

## MVPの前提と注意

- ログインアカウントは環境変数で設定する単一ユーザーです。複数ユーザー登録、パスワード変更・再設定、監査ログは含みません。
- 通知・記録は服薬を保証するものではありません。飲み忘れや重複服薬時の判断、服用量の助言は行いません。処方指示に従い、困った場合は医師・薬剤師へ確認してください。
- 服薬情報は要配慮性の高い個人情報です。DBのアクセス制御、TLS、バックアップの暗号化、ホストのアクセス制限を設定してください。LINEには予定時刻だけを送ります。
- 複数サーバーでの同時起動や高可用性は対象外です。単一インスタンスで利用してください。
- 服用済みボタンは本人の申告を記録します。押し間違いを取り消す機能はまだありません。
# 複数ユーザー登録の追加

既存のNeonデータベースを使っている場合は、`database/v2_multi_user.sql` をSQL Editorで一度実行してください。実行後も `SPRING_FLYWAY_ENABLED=false` のまま起動できます。新規データベースではFlywayが `V1`、`V2` の順に適用します。

アプリ起動時に `APP_AUTH_USERNAME` と `APP_AUTH_PASSWORD` が設定されていると、そのアカウントが初期管理ユーザーとして作成されます。パスワードは12文字以上にしてください。既存のお薬とLINE連携はこの初期管理ユーザーに引き継がれます。`APP_AUTH_PASSWORD` はGoogleやNeonのパスワードではなく、アプリ用に設定する値です。

ログイン画面の「新規登録」からユーザーを追加できます。新規登録したアカウントは一般ユーザーとなり、自分のお薬・服薬記録・LINE連携だけを利用できます。LINE連携は、アプリ内でそのユーザーとしてログインし、連携コードを発行してください。
