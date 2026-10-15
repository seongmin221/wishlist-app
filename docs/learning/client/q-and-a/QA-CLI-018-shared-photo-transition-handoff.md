# QA-CLI-018: 카드 사진이 상세로 커지는 동안 사진이 비지 않게 하려면 무엇이 필요한가?

> 확인 날짜: 2026-10-15 · C5 PR B Task 20~22 · 구현: Android `navigation/PhotoHandoff`·`feature/detail/DetailPhoto`, iOS `Navigation/PhotoHandoff`·`RemoteImageLoader.bestCached(for:)`·`Features/Detail/DetailSharedPhoto` · 기록: [PR B 구현 기록](../../../history/architecture/client/c5-pr-b-implementation-2026-10-15.md)

**질문:** 카드와 상세가 같은 사진 주소를 쓰니 이미지 로더의 메모리 cache에서 꺼내 쓰면 되지 않나? 왜 이미지를 따로 들고, 뒤로 갈 때는 왜 다른 쪽 사진을 그리나?

**답변:** cache만으로는 세 가지가 보장되지 않는다.

1. **key가 다르다.** 두 로더 모두 메모리 key에 표시 크기가 들어간다(Coil은 요청 크기, iOS 로더는 칸 픽셀 크기·채우기 방식). 카드(열 폭)와 상세(1:1 칸 안 여백 56)는 크기가 달라 상세 요청은 cache를 맞히지 못하고 다시 디코드한다. 그동안 커지는 면이 비거나 깜빡인다. 그래서 Android는 상세 요청에 카드 요청의 key를 `placeholderMemoryCacheKey`로 주고, iOS는 주소별 색인으로 "그 주소의 가장 큰 사진"(`bestCached`)을 찾는다.
2. **cache는 비울 수 있다.** 메모리 cache는 축출된다. Coil의 `placeholderMemoryCacheKey`도 cache에 있을 때만 동작한다. 그래서 카드를 누르는 순간 카드가 지금 그리는 이미지 객체를 셸의 handoff에 넣고(메모리 전용), 상세 크기 사진이 그려지고 전환이 끝나면 놓는다. DEBUG `clearImageMemory`로 전환이 시작될 때마다 cache를 비워 이 경로를 확인한다.
3. **전환이 그리는 쪽이 플랫폼마다 다르다.** Compose 공유 요소는 overlay에 **들어오는 쪽** 내용을 그린다. push는 상세, pop은 카드다. 그래서 뒤로 갈 때 카드 칸이 상세가 그리던(더 선명한) 사진을 잠깐 그려야 한다. iOS 전환층은 등록된 내용을 직접 고른다. push는 카드(source), pop·끌어서 뒤로는 상세(target)다.

구현에서 걸린 점:

- 공유 요소의 목표가 **날아가는 도중 다른 노드로 바뀌면**(항목 로딩 화면 → 상품 화면) Compose는 움직이지 않고 끝에서 튄다. 사진 자리는 첫 프레임부터 한 노드로 둔다.
- 뒤로 첫 프레임부터 상세 사진을 그리려면 "뒤로가 시작됐다"를 effect로 알리면 한 프레임 늦다. 상세가 지금 그리는 이미지를 늘 알리고, 카드는 같은 composition에서 navigator 상태로 "그 상세가 떠나는 중"일 때만 쓴다.
- iOS 전환층은 등록된 뷰를 **그 뷰 밖에서 다시 만든다.** `@State`에 든 이미지는 새 뷰에서 비어 있으므로 등록하는 내용은 이미지 값을 받아 그려야 한다.
