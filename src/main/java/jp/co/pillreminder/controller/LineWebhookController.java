package jp.co.pillreminder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class LineWebhookController {

    // LINEからのWebhookリクエストを受け取るエンドポイント
    @PostMapping("/callback")
    public ResponseEntity<String> handleCallback(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody Map<String, Object> request) {

        try {
            // ここでLINEからのイベント（メッセージやボタン押下）を処理する
            System.out.println("Received LINE Webhook: " + request);
            
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }
}
