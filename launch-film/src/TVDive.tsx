import {AbsoluteFill,useCurrentFrame} from 'remotion';
import {Stage} from './Stage';
import {Camera,Room,Television,smooth,lerp3} from './world';
import {Copy} from './Type';
export const TVDive=()=>{const f=useCurrentFrame();return <AbsoluteFill><Stage room><Camera position={lerp3([1.50,1.55,3.15],[0,1.28,1.69],smooth(f/160))} target={[0,1.28,.06]} fov={38}/><Room/><group position={[0,1.28,.02]}><Television on frame={f}/></group></Stage>{f<66&&<Copy eyebrow="NETFLIXPRO FOR TV" headline={'Your next story.\nOn the big screen.'}/>}</AbsoluteFill>;};

