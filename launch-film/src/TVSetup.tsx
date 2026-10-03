import {useCurrentFrame,AbsoluteFill,useVideoConfig,interpolate} from 'remotion';
import {Stage} from './Stage';
import {Camera,Room,Person,Television,smooth,lerp3} from './world';
import {Copy} from './Type';
export const TVSetup=()=>{
 const f=useCurrentFrame();const {width}=useVideoConfig();const place=smooth((f-12)/70),release=smooth((f-95)/35),walk=smooth((f-135)/70);
 return <AbsoluteFill><Stage room><Camera position={lerp3([-3.1,1.9,3.9],[-2.2,1.54,3.9],smooth(f/209))} target={[0,1.04,0]} fov={38}/><Room/><Person place={place} release={release} walk={walk}/><group position={[0,1.28+.5*(1-place),.02]} rotation={[.045*(1-place),0,0]}><Television/></group></Stage>
 <Copy eyebrow="THE RELEASE IS HERE" headline={'Make room\nfor a new story.'} at={125}/><div style={{position:'absolute',bottom:76*width/1920,left:112*width/1920,color:'#bdcad9',fontSize:24*width/1920,opacity:interpolate(f,[0,20,100,125],[0,1,1,0])}}>Android TV · Google TV · Android phone</div></AbsoluteFill>;
};

