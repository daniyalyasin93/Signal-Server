/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.lifecycle.Managed;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.whispersystems.textsecuregcm.configuration.ApnConfiguration;

public class APNSender implements Managed, PushNotificationSender {

  private final ExecutorService executor;
  private final String bundleId;
  private final ApnsClient apnsClient;

  @VisibleForTesting
  static final String APN_VOIP_NOTIFICATION_PAYLOAD    = "{\"aps\":{\"sound\":\"default\",\"alert\":{\"loc-key\":\"APN_Message\"}}}";

  @VisibleForTesting
  static final String APN_NSE_NOTIFICATION_PAYLOAD     = "{\"aps\":{\"mutable-content\":1,\"alert\":{\"loc-key\":\"APN_Message\"}}}";

  @VisibleForTesting
  static final String APN_CHALLENGE_PAYLOAD            = "{\"aps\":{\"sound\":\"default\",\"alert\":{\"loc-key\":\"APN_Message\"}}, \"challenge\" : \"%s\"}";

  @VisibleForTesting
  static final String APN_RATE_LIMIT_CHALLENGE_PAYLOAD = "{\"aps\":{\"sound\":\"default\",\"alert\":{\"loc-key\":\"APN_Message\"}}, \"rateLimitChallenge\" : \"%s\"}";

  @VisibleForTesting
  static final Instant MAX_EXPIRATION = Instant.ofEpochMilli(Integer.MAX_VALUE * 1000L);

  private static final String APNS_CA_FILENAME = "apns-certificates.pem";

  public APNSender(ExecutorService executor, ApnConfiguration configuration)
      throws IOException, NoSuchAlgorithmException, InvalidKeyException
  {
    this.executor = executor;
    this.bundleId = configuration.getBundleId();
    this.apnsClient = new ApnsClientBuilder().setSigningKey(
            ApnsSigningKey.loadFromInputStream(new ByteArrayInputStream(configuration.getSigningKey().getBytes()),
                configuration.getTeamId(), configuration.getKeyId()))
        .setTrustedServerCertificateChain(getClass().getResourceAsStream(APNS_CA_FILENAME))
        .setApnsServer(configuration.isSandboxEnabled() ? ApnsClientBuilder.DEVELOPMENT_APNS_HOST : ApnsClientBuilder.PRODUCTION_APNS_HOST)
        .build();
  }

  @VisibleForTesting
  public APNSender(ExecutorService executor, ApnsClient apnsClient, String bundleId) {
    this.executor = executor;
    this.apnsClient = apnsClient;
    this.bundleId = bundleId;
  }

  @Override
  public CompletableFuture<SendPushNotificationResult> sendNotification(final PushNotification notification) {
    final String topic = switch (notification.tokenType()) {
      case APN -> bundleId;
      case APN_VOIP -> bundleId + ".voip";
      default -> throw new IllegalArgumentException("Unsupported token type: " + notification.tokenType());
    };

    final boolean isVoip = notification.tokenType() == PushNotification.TokenType.APN_VOIP;

    final String payload = switch (notification.notificationType()) {
      case NOTIFICATION -> isVoip ? APN_VOIP_NOTIFICATION_PAYLOAD : APN_NSE_NOTIFICATION_PAYLOAD;
      case CHALLENGE -> String.format(APN_CHALLENGE_PAYLOAD, notification.data());
      case RATE_LIMIT_CHALLENGE -> String.format(APN_RATE_LIMIT_CHALLENGE_PAYLOAD, notification.data());
    };

    final String collapseId =
        (notification.notificationType() == PushNotification.NotificationType.NOTIFICATION && !isVoip)
            ? "incoming-message" : null;

    return apnsClient.sendNotification(new SimpleApnsPushNotification(notification.deviceToken(),
        topic,
        payload,
        MAX_EXPIRATION,
        DeliveryPriority.IMMEDIATE,
        isVoip ? PushType.VOIP : PushType.ALERT,
        collapseId))
        .thenApplyAsync(response -> {
          final boolean accepted;
          final String rejectionReason;
          final boolean unregistered;

          if (response.isAccepted()) {
            accepted = true;
            rejectionReason = null;
            unregistered = false;
          } else {
            accepted = false;
            rejectionReason = response.getRejectionReason().orElse("unknown");
            unregistered = ("Unregistered".equals(rejectionReason) || "BadDeviceToken".equals(rejectionReason));
          }

          return new SendPushNotificationResult(accepted, rejectionReason, unregistered);
        }, executor);
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
    this.apnsClient.close().join();
  }
}
