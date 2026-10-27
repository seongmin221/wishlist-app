# C7 화면 확인

[구현·검증 기록](../c7-home-review-flow-implementation-2026-10-24.md)의 화면 근거다. 보드 원본은 `design/handoff/screens/shots/FHome{L,D}.png`, `FHomeReviewFlow{L,D}.png`, `FHomeFillFlow{L,D}.png`, 카테고리 목록은 `FCategoryList{L,D}.png`다. 픽셀 자동 비교가 아니라 보드와 나란히 보는 육안 비교이며, 기기 논리 크기는 보드 390×844와 다르다(Android ≈393×851dp, iOS 402×874pt).

| 상태 | 보드 | Android (Pixel 5 API 30 에뮬레이터) | iOS (iPhone 17 Pro 시뮬레이터) |
| --- | --- | --- | --- |
| 홈 접힘 | FHome | `android-home-collapsed-{L,D}` | `ios-home-collapsed-{L,D}` |
| 홈 펼침(분류·목적 확인 캐러셀) | FHome | `android-home-expanded-{L,D}` | `ios-home-expanded-{L,D}` |
| 분류·목적 확인 카드 | FHomeReviewFlow | `android-review-card-{L,D}` | `ios-review-card-{L,D}` |
| 끌기 중간 | MotionCardA | `android-review-drag-{L,D}`(`input motionevent`로 손가락 유지) | `ios-review-drag-{L,D}`(dx 120 정지 화면, 회전 중심 수정 후 재촬영) |
| 캐러셀 2번째에서 시작(`1 / 2`) | — | `android-review-from-carousel-D` | — |
| 확인 끝 요약(`넘김 3`) | — | `android-review-summary-{L,D}` | `ios-review-summary-{L,D}` |
| 정보 보완 첫 항목 | FHomeFillFlow | `android-fill-first-{L,D}` | `ios-fill-first-{L,D}` |
| 보완 끝 요약(`건너뜀 3`) | — | `android-fill-summary-{L,D}` | `ios-fill-summary-{L,D}` |
| 카테고리 목록 anchor 복원 | FCategoryList | `android-category-anchor-restored-{L,D}`, `android-category-anchor-foreground-frames-L`(launcher → 복귀 20fps 프레임) | `ios-category-anchor-restored-{L,D}`(DEBUG hook `refreshNow(anchor)`로 긴 목록 8번째가 머리 바로 아래) |

- 두 플랫폼 모두 DEBUG Fake 시드다. 시드 이미지가 없어 사진 자리는 placeholder로 보인다. 요약 화면은 보드가 없어 사용자 답변(넘김·건너뜀 수만)에 따른 구현이다.
- iOS는 UI 자동 조작 도구가 없어 DEBUG 전용 `-wl.c7.screen <state>`로 실제 owner를 몰아 상태를 열었다. 모션은 정지 화면으로만 확인했다.
- 목적 상세 anchor 복원 화면은 없다. Android는 에뮬레이터에서 위치 유지를 눈으로 확인했고, iOS는 시뮬레이터 확인을 하지 않았다.
- Android 첫 anchor 촬영의 이전/이후 쌍은 같은 카드 복원이라 byte 동일이어서 근거로 쓰지 않고 지웠다. 대신 복귀 프레임과 임시 logcat(`sameItem=true`, 같은 scroll 값)으로 확인했다.
