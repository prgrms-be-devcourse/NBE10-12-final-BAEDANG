# investup_app

모의 주식 트레이딩 서비스 모바일 앱 (Flutter · Android/iOS).

배포 빌드는 운영 백엔드(`https://54.180.217.176.sslip.io`)에 연결되어 있습니다.

## 설치 방법

### Android

1. 저장소 루트의 `investup_app.apk`를 폰으로 받습니다(다운로드·메일·메신저 등).
2. 파일을 열면 "출처를 알 수 없는 앱 설치" 허용을 묻습니다 — 허용 후 설치.
3. arm64 빌드라 최근 몇 년 내 안드로이드 폰이면 대부분 동작합니다.

### iOS

iOS는 보안 정책상 파일 전달로 설치할 수 없고 **TestFlight 초대**가 필요합니다.

1. **한민호(@Minho Han)**에게 Slack DM 또는 이메일(mingh662@icloud.com)로
   본인 **Apple ID 이메일 주소**를 보내주세요.
2. TestFlight 초대 메일이 오면 수락 → iPhone에서 **TestFlight** 앱 설치.
3. TestFlight 앱에서 **Investup** 설치.

> TestFlight 빌드는 업로드 후 90일이 지나면 만료됩니다.
> 만료되면 새 빌드를 올려 갱신합니다.

## 로컬 개발

```bash
flutter pub get
flutter run                                   # 기본 http://localhost:8080/api
flutter run --dart-define=API_BASE_URL=http://<host>:8080/api
```

백엔드 주소는 `--dart-define=API_BASE_URL=...`로 지정합니다(반드시 `/api`로 끝나야 함).
안드로이드 에뮬레이터에서 호스트 맥의 서버를 볼 때는 `http://10.0.2.2:8080/api`를 씁니다.

## 릴리즈 빌드

```bash
# Android (릴리즈 키스토어: android/app/upload-keystore.jks + android/key.properties)
flutter build apk --release --split-per-abi \
  --dart-define=API_BASE_URL=https://54.180.217.176.sslip.io/api

# iOS TestFlight용
flutter build ipa --release \
  --export-options-plist=ios/ExportOptions-appstore.plist \
  --dart-define=API_BASE_URL=https://54.180.217.176.sslip.io/api
```

릴리즈는 `API_BASE_URL`이 필수이며 HTTPS만 허용됩니다.
재업로드 시 `pubspec.yaml`의 빌드 번호(`version: 0.1.0+N`)를 올려야 합니다.
