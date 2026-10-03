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
        "⚠️ あれ？「%s」はさっき（2時間以内）も飲んだ記録があるよ！\n飲み過ぎてないか確認してね！無理は禁物だよ☕️",
        "⚠️ ちょっと待った〜！「%s」はさっき飲んだ記録がまだ残ってるよ！\n飲み重ねに気をつけてね💊"
    );

    public LineWebhookController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.restClient = RestClient.builder().baseUrl("https://api.line.me").build();
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> payload) {
        List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
        if (events == null || events.isEmpty()) return ResponseEntity.ok("OK");

        for (Map<String, Object> event : events) {
            String type = (String) event.get("type");
            String replyToken = (String) event.get("replyToken");
            Map<String, Object> source = (Map<String, Object>) event.get("source");
            String lineUserId = (String) source.get("userId");

            if (replyToken == null) continue;

            if ("follow".equals(type)) {
                // 友だち追加イベント
                handleFollow(replyToken, lineUserId);
            } else if ("postback".equals(type)) {
                // ボタンタップイベント
                Map<String, Object> postback = (Map<String, Object>) event.get("postback");
                String data = (String) postback.get("data");
                handlePostback(replyToken, lineUserId, data);
            } else if ("message".equals(type)) {
                // メッセージ送信イベント
                Map<String, Object> message = (Map<String, Object>) event.get("message");
                String text = (String) message.get("text");
                if (text != null && text.contains("飲んだ")) {
                    handleTextIntake(replyToken, lineUserId);
                }
            }
        }
        return ResponseEntity.ok("OK");
    }

    /**
     * 友だち追加時の処理
     */
    private void handleFollow(String replyToken, String lineUserId) {
        // すでに同意済みかチェック（ブロック解除などの再追加対策）
        if (isUserAgreed(lineUserId)) {
            reply(replyToken, "おかえりなさい！🎉\n引き続きお薬マネージャーをご利用いただけます。\n下のメニューからお薬の確認・登録ができますよ💊", null);
            return;
        }

        // ユーザーが存在しなければ初期登録（未同意状態）
        ensureUserExists(lineUserId);

        // 同意を求めるメッセージとクイック返信ボタン
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
            // ★ 同意ボタンが押された時の処理
            handleAgreePrivacy(replyToken, lineUserId);
        } else if ("intake".equals(action) && params.containsKey("medId")) {
            int medId = Integer.parseInt(params.get("medId"));
            String allIdsStr = params.get("allIds");
            processSingleIntake(replyToken, medId, allIdsStr);
        } else if ("intake_all".equals(action) && params.containsKey("medIds")) {
            String[] ids = params.get("medIds").split(",");
            List<String> recordedNames = new ArrayList<>();
            List<String> warnedNames = new ArrayList<>();

            for (String idStr : ids) {
                int medId = Integer.parseInt(idStr);
                String displayName = getDisplayName(medId);

                if (isTakenRecently(medId)) {
                    warnedNames.add(displayName);
                } else {
                    jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", medId);
                    recordedNames.add(displayName);
                }
            }

            StringBuilder reply = new StringBuilder();
            if (!recordedNames.isEmpty()) {
                reply.append("偉い！！🎉 全部まとめてしっかり飲めてナイス！👏\n（").append(String.join("・", recordedNames)).append("）の記録をつけたよ！\n");
            }
            if (!warnedNames.isEmpty()) {
                reply.append("\n⚠️ 以下の薬はさっき（2時間以内）も記録があったよ：\n（").append(String.join("・", warnedNames)).append("）");
            }
            reply(replyToken, reply.toString().trim(), null);
        }
    }

    /**
     * 同意処理（DB更新 ＆ 完了メッセージ送信）
     */
    private void handleAgreePrivacy(String replyToken, String lineUserId) {
        ensureUserExists(lineUserId);

        // 同意フラグを true に更新
        String updateSql = "UPDATE users SET agreed_privacy = true, agreed_at = CURRENT_TIMESTAMP WHERE line_user_id = ?";
        jdbc.update(updateSql, lineUserId);

        String successText = """
            ✅ ご同意ありがとうございます！
            初期設定が完了しました🎉

            下のメニューから、毎日飲むお薬をさっそく登録してみてね💊✨
            あなたの毎日の健康をしっかりサポートするよ！💪
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
        if (!isDuplicate) {
            jdbc.update("INSERT INTO intake_logs (medication_id) VALUES (?)", medId);
        }

        String baseText;
        if (isDuplicate) {
            int idx = ThreadLocalRandom.current().nextInt(WARNING_MESSAGES.size());
            baseText = String.format(WARNING_MESSAGES.get(idx), displayName);
        } else {
            int idx = ThreadLocalRandom.current().nextInt(SUCCESS_MESSAGES.size());
            baseText = String.format(SUCCESS_MESSAGES.get(idx), displayName);
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

    private void handleTextIntake(String replyToken, String lineUserId) {
        String getMedIdSql = """
            SELECT m.id FROM medications m
            JOIN users u ON m.user_id = u.id
            WHERE u.line_user_id = ? AND m.is_active = true
            ORDER BY m.id DESC LIMIT 1
        """;
        List<Integer> medIds = jdbc.queryForList(getMedIdSql, Integer.class, lineUserId);
        if (medIds.isEmpty()) {
            reply(replyToken, "お薬が登録されていないみたいだよ！まずはリッチメニューから登録してね💊", null);
            return;
        }
        processSingleIntake(replyToken, medIds.get(0), null);
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