package jp.co.pillreminder.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping({"/webhook", "/line/webhook"})
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);
    private final JdbcTemplate jdbc;
    private final RestClient restClient;

    @Value("${line.bot.channel-token:${LINE_CHANNEL_ACCESS_TOKEN:}}")
    private String channelToken;

    private static final List<String> SUCCESS_MESSAGES = List.of(
        "偉い！！🎉 「%s」をちゃんと飲めてナイス！\n服薬記録につけておいたよ。自分の体を大切にしてて最高！👏",
        "「%s」のお薬バッチリだね！✨ えらすぎる！\nちゃんと記録しといたよ。コップのお水もしっかり飲んでね☕️",
        "ナイス〜！🙌 「%s」を忘れずに飲めてハナマル満点！💮\n記録完了！マイペースに労わっていこうね💪"
    );

    private static final List<String> WARNING_MESSAGES = List.of(
        "⚠️️ あれ？「%s」はさっき（2時間以内）も飲んだ記録があるよ！\n飲み過ぎてないか確認してね！無理は禁物だよ☕️",
        "⚠️ ちょっと待った〜！「%s」はさっき飲んだ記録がまだ残ってるよ！\n飲み重ねに気をつけてね💊"
    );

    public LineWebhookController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.restClient = RestClient.builder().baseUrl("https://api.line.me").build();
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok("OK");
        }

        for (Map<String, Object> event : events) {
            String type = (String) event.get("type");
            String replyToken = (String) event.get("replyToken");
            Map<String, Object> source = (Map<String, Object>) event.get("source");
            String lineUserId = source != null ? (String) source.get("userId") : null;

            if (replyToken == null || lineUserId == null) {
                continue;
            }

            if ("follow".equals(type)) {
                handleFollow(replyToken, lineUserId);
            } else if ("postback".equals(type)) {
                Map<String, Object> postback = (Map<String, Object>) event.get("postback");
                if (postback != null) {
                    handlePostback(replyToken, lineUserId, (String) postback.get("data"));
                }
            } else if ("message".equals(type)) {
                Map<String, Object> message = (Map<String, Object>) event.get("message");
                String text = (String) message.get("text");
                if (text != null) {
                    if (text.contains("残数") || text.contains("残り") || text.contains("在庫")) {
                        // ★ 残数確認コマンド
                        handleStockInquiry(replyToken, lineUserId);
                    } else if (text.contains("飲んだ")) {
                        // ★ 服薬記録
                        handleTextIntake(replyToken, lineUserId, text);
                    } else if (text.contains("問い合わせ") || text.contains("問合せ") || text.contains("不具合") || text.contains("ヘルプ")) {
                        String helpText = """
                            お問い合わせや不具合のご報告は、以下のフォームより受け付けているよ！👇
                            https://docs.google.com/forms/d/e/1FAIpQLSdK9DkdI7yteceVM5WN17lElmjhEytinXLqryLQujZPrrvF0Q/viewform

                            ※お薬の飲み合わせ等の医療相談にはお答えできないのでご注意ください💊
                            """.stripIndent();
                        reply(replyToken, helpText, null);
                    }
                }
            }
        }
        return ResponseEntity.ok("OK");
    }

    private void handleFollow(String replyToken, String lineUserId) {
        if (isUserAgreed(lineUserId)) {
            reply(replyToken, "おかえりなさい！🎉\n引き続きお薬マネージャーをご利用いただけます。\n下のメニューからお薬の確認・登録ができますよ💊", null);
            return;
        }

        ensureUserExists(lineUserId);

        String welcomeText = """
            【要配慮個人情報の取り扱いについて】
            当アプリでは、薬品名や服用時間などのデータを扱います。
            これらの情報は服薬リマインド通知およびご本人の服薬管理のみに利用し、第三者への提供は一切行いません。

            安心してご利用いただくため、下のボタンをタップして同意をお願いします👇
            """.stripIndent();

        List<Map<String, Object>> quickReplyItems = List.of(
            createPostbackQuickReply("✅ 同意して利用開始", "action=agree_privacy", "同意して利用を開始します")
        );

        reply(replyToken, welcomeText, quickReplyItems);
    }

    private void handlePostback(String replyToken, String lineUserId, String data) {
        Map<String, String> params = parseQueryString(data);
        String action = params.get("action");

        if ("agree_privacy".equals(action)) {
            handleAgreePrivacy(replyToken, lineUserId);
        } else if ("intake".equals(action) && params.containsKey("medId")) {
            int medId = Integer.parseInt(params.get("medId"));
            String allIdsStr = params.get("allIds");
            processSingleIntake(replyToken, medId, allIdsStr);
        } else if ("intake_all".equals(action) && params.containsKey("medIds")) {
            String[] ids = params.get("medIds").split(",");
            List<String> recordedNames = new ArrayList<>();
            List<String> warnedNames = new ArrayList<>();
            List<String> stockAlerts = new ArrayList<>();

            for (String idStr : ids) {
                int medId = Integer.parseInt(idStr);
                String displayName = getDisplayName(medId);

                if (isTakenRecently(medId)) {
                    warnedNames.add(displayName);
                } else {
                    jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", medId);
                    recordedNames.add(displayName);
                    String stockNotice = decrementStockAndGetAlert(medId);
                    if (stockNotice != null) stockAlerts.add(stockNotice);
                }
            }

            StringBuilder reply = new StringBuilder();
            if (!recordedNames.isEmpty()) {
                reply.append("偉い！！🎉 全部まとめてしっかり飲めてナイス！👏\n（").append(String.join("・", recordedNames)).append("）の記録をつけたよ！\n");
            }
            if (!stockAlerts.isEmpty()) {
                reply.append("\n").append(String.join("\n", stockAlerts)).append("\n");
            }
            if (!warnedNames.isEmpty()) {
                reply.append("\n⚠️ 以下の薬はさっき（2時間以内）も記録があったよ：\n（").append(String.join("・", warnedNames)).append("）");
            }
            reply(replyToken, reply.toString().trim(), null);
        }
    }

    private void handleAgreePrivacy(String replyToken, String lineUserId) {
        ensureUserExists(lineUserId);

        String updateSql = "UPDATE users SET agreed_privacy = true, agreed_at = CURRENT_TIMESTAMP WHERE line_user_id = ?";
        jdbc.update(updateSql, lineUserId);

        String successText = """
            ✅ ご同意ありがとうございます！
            初期設定が完了しました🎉

            下のメニューから、毎日飲むお薬をさっそく登録してみてね💊✨
            あなたの毎日の健康をしっかりサポートするよ！💪

            ━━━━━━━━━━━━━━
            💡 画面下にメニューが出ない場合：
            一度トーク一覧に戻って開き直すか、下のリンクから直接お薬マネージャーを開いてね👇
            https://liff.line.me/2011761175-lcQ9D2JG
            """.stripIndent();

        reply(replyToken, successText, null);
    }

    private void ensureUserExists(String lineUserId) {
        String upsertSql = """
            INSERT INTO users (line_user_id, agreed_privacy)
            VALUES (?, false)
            ON CONFLICT (line_user_id) DO NOTHING
        """;
        jdbc.update(upsertSql, lineUserId);
    }

    private boolean isUserAgreed(String lineUserId) {
        try {
            String sql = "SELECT agreed_privacy FROM users WHERE line_user_id = ?";
            Boolean agreed = jdbc.queryForObject(sql, Boolean.class, lineUserId);
            return Boolean.TRUE.equals(agreed);
        } catch (Exception e) {
            return false;
        }
    }

    private void processSingleIntake(String replyToken, int medId, String allIdsStr) {
        String displayName = getDisplayName(medId);
        boolean isDuplicate = isTakenRecently(medId);

        String stockNotice = null;
        if (!isDuplicate) {
            jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", medId);
            stockNotice = decrementStockAndGetAlert(medId);
        }

        String baseText;
        if (isDuplicate) {
            int idx = ThreadLocalRandom.current().nextInt(WARNING_MESSAGES.size());
            baseText = String.format(WARNING_MESSAGES.get(idx), displayName);
        } else {
            int idx = ThreadLocalRandom.current().nextInt(SUCCESS_MESSAGES.size());
            baseText = String.format(SUCCESS_MESSAGES.get(idx), displayName);
            if (stockNotice != null) {
                baseText += "\n\n" + stockNotice;
            }
        }

        List<String> remainIdList = new ArrayList<>();
        if (allIdsStr != null && !allIdsStr.isBlank()) {
            for (String id : allIdsStr.split(",")) {
                if (!id.trim().equals(String.valueOf(medId))) {
                    remainIdList.add(id.trim());
                }
            }
        }

        if (!remainIdList.isEmpty()) {
            String newAllIdsStr = String.join(",", remainIdList);
            List<Map<String, Object>> quickReplyItems = new ArrayList<>();
            List<String> remainingDisplayNames = new ArrayList<>();

            for (String rIdStr : remainIdList) {
                int rId = Integer.parseInt(rIdStr);
                String rDisplayName = getDisplayName(rId);
                remainingDisplayNames.add(rDisplayName);

                String label = "✅ " + (rDisplayName.length() > 8 ? rDisplayName.substring(0, 8) + ".." : rDisplayName) + " 飲んだ";
                String pData = "action=intake&medId=" + rId + "&allIds=" + newAllIdsStr;
                quickReplyItems.add(createPostbackQuickReply(label, pData, rDisplayName + " 飲んだ！"));
            }

            if (remainIdList.size() > 1 && quickReplyItems.size() < 13) {
                quickReplyItems.add(createPostbackQuickReply(
                    "✨ 残りを全部飲んだ！",
                    "action=intake_all&medIds=" + newAllIdsStr,
                    "残りを全部飲んだ！"
                ));
            }

            String remainNotice = "\n\n📌 まだ「" + String.join("・", remainingDisplayNames) + "」が残っているよ！\n飲んだら下のボタンをタップしてね💊";
            reply(replyToken, baseText + remainNotice, quickReplyItems);
        } else {
            if (allIdsStr != null && allIdsStr.contains(",")) {
                String allDoneNotice = "\n\n🎉 これでこの時間のお薬はすべて完了！パーフェクト！✨";
                reply(replyToken, baseText + allDoneNotice, null);
            } else {
                reply(replyToken, baseText, null);
            }
        }
    }

    private void handleTextIntake(String replyToken, String lineUserId, String text) {
        // ユーザーのお薬一覧を取得（テキスト内に薬名が含まれていればそれを優先）
        String sql = """
            SELECT m.id, m.name FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ? AND m.is_active = true
            ORDER BY m.id DESC
        """;
        List<Map<String, Object>> meds = jdbc.queryForList(sql, lineUserId);
        if (meds.isEmpty()) {
            reply(replyToken, "お薬が登録されていないみたいだよ！まずはリッチメニューから登録してね💊", null);
            return;
        }

        Integer targetMedId = null;
        for (Map<String, Object> m : meds) {
            String medName = (String) m.get("name");
            if (text.contains(medName)) {
                targetMedId = (Integer) m.get("id");
                break;
            }
        }
        if (targetMedId == null) {
            targetMedId = (Integer) meds.get(0).get("id");
        }

        processSingleIntake(replyToken, targetMedId, null);
    }

    // ★ 残数確認コマンドの処理
    private void handleStockInquiry(String replyToken, String lineUserId) {
        String sql = """
            SELECT m.name, COALESCE(m.show_name, true) AS show_name,
                   COALESCE(m.stock_quantity, 0) AS stock_quantity,
                   COALESCE(m.low_stock_alert, 5) AS low_stock_alert,
                   COALESCE(m.is_as_needed, false) AS is_as_needed
            FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ? AND m.is_active = true
            ORDER BY m.is_as_needed ASC, m.notify_time ASC
        """;
        List<Map<String, Object>> meds = jdbc.queryForList(sql, lineUserId);
        if (meds.isEmpty()) {
            reply(replyToken, "現在登録されているお薬はありません💊\n下のメニューから登録してね！", null);
            return;
        }

        StringBuilder sb = new StringBuilder("📋 現在のお薬の残数一覧だよ！\n━━━━━━━━━━━━━━\n");
        boolean hasLowStock = false;
        for (Map<String, Object> m : meds) {
            boolean showName = (Boolean) m.get("show_name");
            String name = showName ? (String) m.get("name") : "お薬";
            int stock = ((Number) m.get("stock_quantity")).intValue();
            int lowAlert = ((Number) m.get("low_stock_alert")).intValue();
            boolean isAsNeeded = (Boolean) m.get("is_as_needed");

            sb.append("・").append(name);
            if (isAsNeeded) sb.append(" [頓服]");
            sb.append(": あと ").append(stock).append(" 錠");

            if (stock <= 0) {
                sb.append(" ❌【在庫なし】");
                hasLowStock = true;
            } else if (stock <= lowAlert) {
                sb.append(" ⚠️【残少】");
                hasLowStock = true;
            }
            sb.append("\n");
        }
        sb.append("━━━━━━━━━━━━━━");
        if (hasLowStock) {
            sb.append("\n⚠️ 残りわずかのお薬があるよ！受診やお薬の準備を忘れないでね🏥");
        }
        reply(replyToken, sb.toString().trim(), null);
    }

    // ★ 残数を減らして通知文を生成
    private String decrementStockAndGetAlert(int medId) {
        try {
            jdbc.update(
                "UPDATE medications SET stock_quantity = GREATEST(0, stock_quantity - COALESCE(decrement_amount, 1)) WHERE id = ?",
                medId
            );
            Map<String, Object> m = jdbc.queryForMap(
                "SELECT name, COALESCE(show_name, true) AS show_name, stock_quantity, low_stock_alert FROM medications WHERE id = ?",
                medId
            );
            boolean showName = (Boolean) m.get("show_name");
            String name = showName ? (String) m.get("name") : "お薬";
            int stock = ((Number) m.get("stock_quantity")).intValue();
            int lowAlert = ((Number) m.get("low_stock_alert")).intValue();

            if (stock <= 0) {
                return String.format("⚠️️ 「%s」の残りが【0錠】になったよ！お薬を補充してね！", name);
            } else if (stock <= lowAlert) {
                return String.format("⚠️ 「%s」が残り【あと%d錠】だよ！そろそろ病院や薬局へ行こう🏥", name, stock);
            } else {
                return String.format("📦 「%s」の残りは【あと%d錠】だよ！", name, stock);
            }
        } catch (Exception e) {
            log.error("残数更新エラー: {}", e.getMessage());
            return null;
        }
    }

    private String getDisplayName(int medId) {
        try {
            Map<String, Object> map = jdbc.queryForMap(
                "SELECT name, COALESCE(show_name, true) AS show_name FROM medications WHERE id = ?",
                medId
            );
            boolean showName = (Boolean) map.get("show_name");
            return showName ? (String) map.get("name") : "お薬";
        } catch (Exception e) {
            return "お薬";
        }
    }

    private boolean isTakenRecently(int medId) {
        String checkSql = "SELECT COUNT(*) FROM intake_logs WHERE medication_id = ? AND taken_at >= (CURRENT_TIMESTAMP - INTERVAL '2 hour')";
        Integer count = jdbc.queryForObject(checkSql, Integer.class, medId);
        return count != null && count > 0;
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private Map<String, Object> createPostbackQuickReply(String label, String data, String displayText) {
        return Map.of(
            "type", "action",
            "action", Map.of(
                "type", "postback",
                "label", label,
                "data", data,
                "displayText", displayText
            )
        );
    }

    private void reply(String replyToken, String text, List<Map<String, Object>> quickReplyItems) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "text");
        message.put("text", text);
        if (quickReplyItems != null && !quickReplyItems.isEmpty()) {
            message.put("quickReply", Map.of("items", quickReplyItems));
        }

        try {
            restClient.post().uri("/v2/bot/message/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(channelToken))
                .body(Map.of("replyToken", replyToken, "messages", List.of(message)))
                .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.error("返信エラー: {}", e.getMessage());
        }
    }
}
