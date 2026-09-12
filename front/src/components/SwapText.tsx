import type { ReactNode } from 'react';

/**
 * 버튼 안에 넣으면, 부모 요소에 "iv-hover-swap" 클래스가 있을 때 마우스를
 * 올리거나(:hover) 키보드로 포커스하면(:focus-visible) 텍스트가 "아래에서
 * 위로 한 번 휙 바뀌는" 것처럼 보이는 컴포넌트 — toss.im/#ads 참고.
 *
 * 같은 텍스트를 두 벌(iv-swap-original/iv-swap-hover) 겹쳐두고 각각
 * translateY만 반대로 움직여서 "텍스트가 위로 빠지며 동시에 같은 텍스트가
 * 아래에서 올라와 그 자리를 채우는" 것처럼 보이게 한다 — 실제로는 두 개의
 * 서로 다른 엘리먼트가 스쳐 지나가는 것이라, 하나의 텍스트에 opacity를 주는
 * fade보다 "실제로 이동하는" 느낌이 난다. 애니메이션 값(지속시간·이징)은
 * investup-intro.css의 .iv-swap-original/.iv-swap-hover에서 조절한다.
 *
 * CSS(:hover/:focus-visible)만으로 동작해서 JS로 hover 상태를 따로 관리하지
 * 않는다 — 마우스를 빠르게 넣었다 뺐다 해도 브라우저가 트랜지션을 알아서
 * 중단·역재생해주므로 버벅이거나 겹쳐 재생되지 않는다.
 */
export function SwapText({ children }: { children: ReactNode }) {
  return (
    <span className="iv-swap">
      <span className="iv-swap-original">{children}</span>
      <span className="iv-swap-hover" aria-hidden="true">
        {children}
      </span>
    </span>
  );
}
