# QA-CLI-017: 카테고리 이름 "최대 40자"는 무엇을 세고, 한글 입력 중에는 어떻게 자르는가?

> 확인 날짜: 2026-10-15 · C5 Task 2·10·15 · 구현: `CategoryInputRules`(shared), Android `WLInput(limitUnit = CodePoint)`, iOS `WLInput(limitUnit: .codePoint)` · 계약: [CAT-03 입력](../../../architecture/server/category-management-api.md#cat-03-생성과-replay)

**질문:** 서버는 이름 40·설명 200·예시 60을 Unicode code point로 센다. 화면의 글자 수(grapheme)와 Kotlin·Swift 문자열 길이(UTF-16)는 각각 다르다. 무엇에 맞추고, 한글을 조합하는 중에는 어떻게 하는가?

**답변:** 서버 기준인 code point에 맞춘다. 셋은 이렇게 다르다.

| 입력 | grapheme(보이는 글자) | code point | UTF-16 |
| --- | --- | --- | --- |
| `가` | 1 | 1 | 1 |
| `😀` | 1 | 1 | 2 |
| `👨‍👩‍👧‍👦`(가족 emoji) | 1 | 7 | 11 |
| NFD `각`(자모 3개) | 1 | 3 | 3 |
| `\r\n` | 1 | 2 | 2 |

- 화면 글자 수로 40을 막으면 emoji 이름이 서버에서 422가 나고, UTF-16으로 막으면 emoji를 절반만 쓸 수 있다.
- 입력칸은 IME 조합이 끝난 확정 글자에만 제한을 건다(기존 `WLInput` 방식). 조합 중인 `ㅎ→하→한`을 자르면 입력이 깨지기 때문이다.
- 넘친 글자는 **code point 상한 안에 들어가는 마지막 grapheme 경계**에서 자른다. code point로 바로 자르면 ZWJ emoji가 반쪽 사람만 남거나 결합 문자가 떨어진다. 대가로 상한 근처의 emoji 묶음은 통째로 빠진다.
- 저장 직전에도 shared `CategoryInputRules`로 다시 검사한다. 금지 문자(제어문자·U+200B·bidi 제어 등)는 입력칸이 아니라 이 검사가 잡고 "쓸 수 없는 문자가 들어 있어요"를 보인다.
