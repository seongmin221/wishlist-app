# C4 의존성: Coil 3 (2026-10-10)

상품 상세·카드 사진을 Android에서 그리기 위해 Coil 3만 새로 추가했다(D5, 사용자 승인). iOS는 의존성 없이 직접 만든 `RemoteImageLoader`를 쓴다.

## 선택

- `io.coil-kt.coil3:coil-compose`, `io.coil-kt.coil3:coil-network-okhttp` **3.5.0**.
- 3.6.3(최신)은 `:android:assembleDebug`의 AAR metadata 검사에서 실패했다. 3.6.x가 끌어오는 Compose 1.12.0 계열이 `compileSdk 37`과 AGP 9.1.0 이상을 요구하는데, 이 프로젝트는 AGP 9.0.0·`compileSdk 36`이다.
- 3.5.0과 3.4.0은 Kotlin 2.3.21·AGP 9.0.0에서 `assembleDebug` 성공. 최신 안정판 중 이 조합에서 빌드되는 3.5.0을 골랐다.
- AGP를 9.1 이상으로 올리는 때 3.6.x를 다시 검토한다.

## 네트워크

- `coil-network-okhttp`가 쓰는 OkHttp는 Ktor OkHttp 엔진이 이미 끌어오는 `okhttp` 5.3.2로 해소된다(`okhttp:4.12.0 -> 5.3.2`). 별도 Ktor 네트워크 모듈은 넣지 않았다.

## 자리표시(D9)

- 주소가 없거나 http/https가 아니거나 로딩·실패이면 카드색 면 + 중립 가방 윤곽 아이콘. 디자인시스템에 상품 아이콘이 없어 `ProductPhoto`(Android·iOS) 안에서만 24 격자 선으로 그린다.
