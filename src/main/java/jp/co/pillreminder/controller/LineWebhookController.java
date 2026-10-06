package jp.co.pillreminder.controller;

import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.PostbackEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
public class LineWebhookController {

    // LINEからのWebhookリクエストを受け取るエンドポイント
    @PostMapping("/callback")
    public ResponseEntity<String> handleCallback(
            @RequestHeader("X-Line-Signature") String signature,
            @RequestBody CallbackRequest request) {

        try {
            List<Event> events = request.events();
            for (Event event : events) {
                if (event instanceof MessageEvent) {
                    handleMessageEvent((MessageEvent) event);
                } else if (event instanceof PostbackEvent) {
                    handlePostbackEvent((PostbackEvent) event);
                }
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    private void handleMessageEvent(MessageEvent event) {
        String userId = event.source().userId();
        var message = event.message();

        if (message instanceof TextMessageContent) {
            String text = ((TextMessageContent) message).text();
            // テキストメッセージに応じた処理（必要に応じてサービス層を呼び出し）
            System.out.println("Received message from " + userId + ": " + text);
        }
    }

    private void handlePostbackEvent(PostbackEvent event) {
        String userId = event.source().userId();
        String data = event.postback().data();
        
        // カレンダーやリマインダーのボタン押下（ポストバック）に応じた処理
        System.out.println("Received postback from " + userId + ": " + data);
    }
}
