import {AbsoluteFill,useCurrentFrame} from 'remotion';
import {Stage} from './Stage';
import {Camera,Remote,Television,Floor,smooth,lerp3} from './world';
import {Copy} from './Type';
export const RemoteReveal=()=>{const f=useCurrentFrame(),p=smooth(f/119),click=Math.sin(Math.PI*smooth((f-75)/12));return <AbsoluteFill><Stage><Camera position={lerp3([1.6,1.8,3.3],[.7,1.6,2.4],p)} target={[0,1.05,0]} fov={35}/><Floor/><group position={[.40,1.12,.22]} rotation={[-.04,-.45+.8*p,-.18]} scale={1.7}><Remote click={click}/></group><group position={[-.6,1.1,-1.8]} rotation={[0,.12,0]}><Television on={f>90} still/></group></Stage><Copy eyebrow="YOUR EVENING. ONE CLICK." headline="Settle in." at={12}/></AbsoluteFill>;};

