package com.dailygames;

interface EmailSender {
  void sendVerification(String recipientEmail, String verificationLink, int linkHours);
  void sendPasswordReset(String recipientEmail, String resetLink, int linkMinutes);
}
