import {ThreeCanvas} from '@remotion/three';
import {useVideoConfig} from 'remotion';
import * as T from 'three';
import {Lights} from './world';
export const Stage=({children,room=false}:{children:React.ReactNode;room?:boolean})=>{
 const {width,height}=useVideoConfig();
 return <ThreeCanvas width={width} height={height} shadows dpr={1} camera={{fov:40,near:.01,far:80,position:[0,1.5,4]}} gl={{antialias:true,alpha:false,preserveDrawingBuffer:true,toneMapping:T.ACESFilmicToneMapping,toneMappingExposure:1.1}}>
 <color attach="background" args={['#080e17']}/><fog attach="fog" args={['#080e17',8,25]}/><Lights warm={room}/>{children}</ThreeCanvas>;
};

