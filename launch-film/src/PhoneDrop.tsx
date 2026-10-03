import {AbsoluteFill,useCurrentFrame} from 'remotion';
import {Stage} from './Stage';
import {Camera,Phone,Hand,Limb,Floor,smooth,mix} from './world';
import {Copy} from './Type';
export const PhoneDrop=()=>{const f=useCurrentFrame(),fall=smooth((f-8)/46),caught=smooth((f-54)/16),recover=smooth((f-70)/45),y=2.7-1.5*fall-.075*Math.sin(Math.PI*caught);return <AbsoluteFill><Stage><Camera position={[1.22,2.35,3.5]} target={[.15,1.5,0]} fov={34}/><Floor/><group position={[.25,1.13,0]} rotation={[-Math.PI/2,0,-.05]} scale={2.1}><Hand curl={mix(.15,.65,caught)}/></group><Limb a={[.25,.86,-.15]} b={[.36,.12,-.61]} r={.10} endRadius={.09} color="#263441"/><group position={[.25,y,.06]} rotation={[mix(-.90,-.22,recover),mix(-1.3,.12,fall),mix(.7,-.12,fall)]}><Phone/></group></Stage><Copy eyebrow="AND JUST LIKE THAT" headline={'A world of stories.\nIn your hand.'} at={60}/></AbsoluteFill>;};

