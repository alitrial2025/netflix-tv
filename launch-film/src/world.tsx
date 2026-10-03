import {useEffect, useLayoutEffect, useMemo, useState} from 'react';
import {useThree} from '@react-three/fiber';
import {continueRender, delayRender, cancelRender, staticFile} from 'remotion';
import * as T from 'three';
export type V3 = [number, number, number];
export const smooth=(t:number)=>{const v=Math.max(0,Math.min(1,t));return v*v*(3-2*v);};
export const mix=(a:number,b:number,t:number)=>a+(b-a)*t;
export const lerp3=(a:V3,b:V3,t:number):V3=>[mix(a[0],b[0],t),mix(a[1],b[1],t),mix(a[2],b[2],t)];
export const Camera=({position,target,fov=40}:{position:V3;target:V3;fov?:number})=>{
 const {camera}=useThree();
 useLayoutEffect(()=>{camera.position.set(...position);camera.lookAt(...target);if(camera instanceof T.PerspectiveCamera){camera.fov=fov;camera.updateProjectionMatrix();}camera.updateMatrixWorld();},[camera,position,target,fov]);return null;
};
// Wait for the exact Kotlin Compose frame, then synchronously repaint ThreeCanvas.
const LoadedScreen=({path,w,h}:{path:string;w:number;h:number})=>{
 const [handle]=useState(()=>delayRender(`Kotlin UI: ${path}`));
 const [texture,setTexture]=useState<T.Texture|null>(null);const advance=useThree(s=>s.advance);
 useEffect(()=>{let alive=true;let loaded:T.Texture|undefined;
  new T.TextureLoader().load(staticFile(path),value=>{loaded=value;value.colorSpace=T.SRGBColorSpace;value.anisotropy=8;value.minFilter=T.LinearFilter;value.magFilter=T.LinearFilter;if(alive)setTexture(value);else value.dispose();},undefined,e=>cancelRender(e));
  return()=>{alive=false;loaded?.dispose();continueRender(handle);};
 },[handle,path]);
 useLayoutEffect(()=>{if(texture){advance(performance.now());continueRender(handle);}},[texture,advance,handle]);
 return <mesh><planeGeometry args={[w,h]}/><meshBasicMaterial map={texture} color={texture?'white':'#080b10'} toneMapped={false}/></mesh>;
};
export const NativeScreen=({device,frame,w,h,still=false}:{device:'tv'|'mobile'|'mobile-details';frame:number;w:number;h:number;still?:boolean})=>{
 const file=still?`screens/${device}-home.png`:`screens/${device === 'mobile-details' ? device : device + '-home'}/${String(Math.max(0,Math.min(59,Math.floor(frame/2)))).padStart(5,'0')}.png`;
 return <LoadedScreen key={file} path={file} w={w} h={h}/>;
};
export const Lights=({warm=false}:{warm?:boolean})=><>
 <ambientLight intensity={.48}/><hemisphereLight args={['#ccecff','#12151f',.65]}/>
 <directionalLight position={[-3,5,4]} intensity={2.5} color={warm?'#ffe1bc':'#ecf7ff'} castShadow shadow-mapSize={[2048,2048]} shadow-camera-left={-5} shadow-camera-right={5} shadow-camera-top={5} shadow-camera-bottom={-5} shadow-bias={-.0002}/>
 <pointLight position={[3,2,-1]} intensity={18} distance={10} color="#329ddd"/><pointLight position={[-3,2,-2]} intensity={12} distance={9} color="#e7aa6c"/>
 <rectAreaLight position={[0,4,3]} width={5} height={3} intensity={3} color="#edf9ff"/>
</>;
export const Block=({size,position=[0,0,0],color='#171e26',radius=.025,metalness=.3,roughness=.35}:{size:V3;position?:V3;color?:string;radius?:number;metalness?:number;roughness?:number})=>{
 const geometry=useMemo(()=>{
  const [w,h,d]=size,r=Math.min(radius,w/2,h/2),x=-w/2,y=-h/2;
  const shape=new T.Shape();shape.moveTo(x+r,y);shape.lineTo(x+w-r,y);shape.quadraticCurveTo(x+w,y,x+w,y+r);shape.lineTo(x+w,y+h-r);shape.quadraticCurveTo(x+w,y+h,x+w-r,y+h);shape.lineTo(x+r,y+h);shape.quadraticCurveTo(x,y+h,x,y+h-r);shape.lineTo(x,y+r);shape.quadraticCurveTo(x,y,x+r,y);
  const geo=new T.ExtrudeGeometry(shape,{depth:d,bevelEnabled:false,curveSegments:5,steps:1});geo.translate(0,0,-d/2);return geo;
 },[size[0],size[1],size[2],radius]);
 useEffect(()=>()=>geometry.dispose(),[geometry]);
 return <mesh geometry={geometry} position={position} castShadow receiveShadow><meshStandardMaterial color={color} metalness={metalness} roughness={roughness}/></mesh>;
};
export const Limb=({a,b,r=.04,color='#915d40',endRadius}:{a:V3;b:V3;r?:number;color?:string;endRadius?:number})=>{
 const av=new T.Vector3(...a),bv=new T.Vector3(...b),delta=bv.clone().sub(av),q=new T.Quaternion().setFromUnitVectors(new T.Vector3(0,1,0),delta.clone().normalize());
 return <mesh position={av.add(bv).multiplyScalar(.5)} quaternion={q} castShadow><cylinderGeometry args={[endRadius??r,r,delta.length(),14]}/><meshStandardMaterial color={color} roughness={.65}/></mesh>;
};
export const Oval=({position,scale,color,roughness=.55}:{position:V3;scale:V3;color:string;roughness?:number})=><mesh position={position} scale={scale} castShadow><sphereGeometry args={[1,24,20]}/><meshStandardMaterial color={color} roughness={roughness}/></mesh>;
// Articulated palms, thumbs, knuckles and curled finger joints are genuine meshes.
export const Hand=({curl=.5,side=1}:{curl?:number;side?:number})=><group scale={[side,1,1]}>
 <Oval position={[0,0,0]} scale={[.075,.105,.028]} color="#946746"/>
 {[0,1,2,3].map(i=>{const x=(i-1.5)*.036,length=[.087,.103,.096,.077][i];const a:V3=[x,.070,.003],b:V3=[x,.070+length*.56,-.015*curl],c:V3=[x,.070+length*(1-.30*curl),.045*curl];return <group key={i}><Limb a={a} b={b} r={.015} endRadius={.013}/><Oval position={b} scale={[.015,.018,.016]} color="#946746"/><Limb a={b} b={c} r={.013} endRadius={.010}/><Oval position={c} scale={[.011,.017,.011]} color="#946746"/></group>;})}
 <Limb a={[-.055,-.012,0]} b={[-.096,.037,.025]} r={.023} endRadius={.018}/><Limb a={[-.096,.037,.025]} b={[-.068,.074,.053*curl]} r={.018} endRadius={.014}/>
</group>;
export const Person=({place,release,walk}:{place:number;release:number;walk:number})=>{
 const sway=Math.sin(walk*17)*.035,y=1.50-.025*Math.sin(release*Math.PI),gripY=1.18+.5*(1-place);
 const handL=lerp3([-.99,gripY,.055],[-.34,1.04,-.40],release),handR=lerp3([.99,gripY,.055],[.34,1.04,-.40],release);
 return <group position={[walk*3.6,sway,-.33-walk*.3]} rotation={[0,walk*.9,0]}>
 <Oval position={[0,1.32,-.12]} scale={[.265,.38,.16]} color="#242b32"/><Oval position={[0,1.58,-.09]} scale={[.29,.16,.15]} color="#242b32"/>
 <Limb a={[0,1.63,-.10]} b={[0,1.72,-.10]} r={.064}/><Oval position={[0,1.82,-.10]} scale={[.102,.14,.10]} color="#946746"/><Oval position={[0,1.89,-.13]} scale={[.106,.081,.095]} color="#131715"/><Oval position={[0,1.805,-.006]} scale={[.028,.031,.037]} color="#946746"/>
 {[-1,1].map(side=>{const hand=side<0?handL:handR,shoulder:V3=[side*.26,y,-.10],elbow:V3=[side*mix(.60,.30,release),mix(gripY+.13,1.22,release),-.13],hip:V3=[side*.12,.94,-.14],phase=Math.sin(walk*17+side*Math.PI/2)*.20*release,knee:V3=[side*.13,.53,-.12+phase],ankle:V3=[side*.15,.09,-.13-phase];return <group key={side}>
 <Limb a={shoulder} b={elbow} r={.078} endRadius={.060} color="#242b32"/><Oval position={elbow} scale={[.065,.065,.065]} color="#242b32"/><Limb a={elbow} b={hand} r={.048} endRadius={.032}/>
 <group position={hand} rotation={[0,side*Math.PI/2,-side*.15]}><Hand curl={1-release*.65} side={side}/></group>
 <Limb a={hip} b={knee} r={.085} endRadius={.070} color="#19202a"/><Limb a={knee} b={ankle} r={.060} endRadius={.044} color="#19202a"/><Block size={[.14,.08,.26]} position={[ankle[0],.045,ankle[2]+.045]} color="#11171e"/>
 </group>;})}</group>;
};
export const BrandMark=({scale=1}:{scale?:number})=>{
 const tri=useMemo(()=>new T.Shape([new T.Vector2(-.03,-.055),new T.Vector2(-.03,.055),new T.Vector2(.07,0)]),[]);
 return <group scale={scale}><Block size={[.27,.27,.008]} radius={.045} color="#1761b0" roughness={.25}/><mesh position={[0,0,.008]}><shapeGeometry args={[tri]}/><meshBasicMaterial color="white" toneMapped={false}/></mesh><mesh position={[.086,.086,.009]}><circleGeometry args={[.013,24]}/><meshBasicMaterial color="#82d7ff" toneMapped={false}/></mesh></group>;
};
export const Television=({on=false,frame=0,still=false}:{on?:boolean;frame?:number;still?:boolean})=><group>
 <Block size={[2.17,1.25,.060]} color="#0b1017" radius={.017} metalness={.85} roughness={.22}/><Block size={[2.14,1.22,.062]} color="#343e4b" radius={.011} metalness={.9}/>
 <group position={[0,0,.033]}>{on?<NativeScreen device="tv" frame={frame} w={2.105} h={1.184} still={still}/>:<><mesh><planeGeometry args={[2.105,1.184]}/><meshPhysicalMaterial color="#03080c" metalness={.5} roughness={.15} clearcoat={1}/></mesh><group position={[0,0,.002]}><BrandMark scale={.7}/></group></>}</group>
 {[-1,1].map(side=><group key={side}><Limb a={[side*.70,-.59,0]} b={[side*.83,-.73,.12]} r={.017} color="#161c24"/><Limb a={[side*.70,-.59,0]} b={[side*.64,-.73,-.16]} r={.017} color="#161c24"/></group>)}
 <mesh position={[0,-.608,.034]}><sphereGeometry args={[.004,8,8]}/><meshBasicMaterial color={on?'#82d7ff':'#737f8c'}/></mesh>
</group>;
export const Remote=({click=0}:{click?:number})=><group>
 <Block size={[.24,.85,.050]} radius={.06} metalness={.65} roughness={.22}/><Block size={[.23,.83,.052]} radius={.054} color="#242e38" metalness={.6}/>
 <mesh position={[0,.12,.029]} rotation={[Math.PI/2,0,0]}><cylinderGeometry args={[.073,.073,.008,48]}/><meshStandardMaterial color="#0a1119" roughness={.33} metalness={.3}/></mesh>
 <mesh position={[0,.12,.037-click*.008]} rotation={[Math.PI/2,0,0]}><circleGeometry args={[.040,32]}/><meshStandardMaterial color="#8bcce7" metalness={.7} roughness={.25}/></mesh>
 <mesh position={[-.055,.30,.032]}><sphereGeometry args={[.023,20,16]}/><meshStandardMaterial color="#b0bdc6" metalness={.75} roughness={.25}/></mesh>
 {[0,1,2].map(y=>[-1,1].map(x=><Block key={`${x}${y}`} size={[.065,.033,.013]} position={[x*.050,-.08-y*.085,.028]} radius={.01} color={y===2?'#1761b0':'#4f5b66'}/>))}<group position={[0,-.33,.032]}><BrandMark scale={.15}/></group>
</group>;
export const Phone=({on=false,frame=0,still=false,details=false}:{on?:boolean;frame?:number;still?:boolean;details?:boolean})=><group>
 <Block size={[.44,.918,.043]} radius={.057} color="#aebbc6" metalness={.88} roughness={.21}/><Block size={[.430,.906,.046]} radius={.050} color="#080d14" metalness={.25} roughness={.3}/>
 <group position={[0,0,.026]}>{on?<NativeScreen device={details?'mobile-details':'mobile'} frame={frame} w={.401} h={.872} still={still}/>:<><mesh><planeGeometry args={[.401,.872]}/><meshBasicMaterial color="#040b13"/></mesh><group position={[0,0,.001]}><BrandMark scale={.66}/></group></>}</group>
 <Block size={[.08,.016,.003]} position={[0,.433,.029]} radius={.007} color="#04060a"/><Block size={[.004,.088,.012]} position={[.222,.19,0]} radius={.001} color="#c2d1df"/><Block size={[.004,.06,.012]} position={[-.222,.20,0]} radius={.001} color="#c2d1df"/><Block size={[.11,.026,.004]} position={[0,-.419,.029]} radius={.012} color="#ecf4fa"/>
</group>;
export const Floor=()=><mesh rotation={[-Math.PI/2,0,0]} position={[0,-.005,0]} receiveShadow><planeGeometry args={[50,50]}/><meshStandardMaterial color="#101722" metalness={.45} roughness={.3}/></mesh>;
export const Room=()=><>
 <Floor/><Block size={[12,5,.12]} position={[0,2.4,-2.2]} color="#181f28" metalness={.12} roughness={.85}/>
 {Array.from({length:15},(_,i)=><Block key={i} size={[.045,3,.060]} position={[2.15+i*.12,1.65,-2.1]} color="#4d3f35" metalness={.15} roughness={.7}/>)}
 <Block size={[2.68,.105,.68]} position={[0,.545,-.06]} color="#715340" metalness={.1} roughness={.55}/><Block size={[2.61,.36,.62]} position={[0,.326,-.06]} color="#3e302b" metalness={.05} roughness={.67}/>
 {[-1,1].map(x=><Block key={x} size={[.04,.10,.04]} position={[x*1.17,.08,-.04]} color="#b89468" metalness={.8}/>)}
 {[-1,0,1].map(x=><Block key={x} size={[.81,.28,.006]} position={[x*.854,.32,.255]} color="#655043" radius={.004} metalness={.05} roughness={.63}/>)}
 <mesh position={[0,.16,.27]}><planeGeometry args={[2.4,.014]}/><meshBasicMaterial color="#936e3d"/></mesh>
 <Block size={[.018,2.10,.018]} position={[-2.03,1.05,-.55]} color="#bba27f" metalness={.8}/><mesh position={[-2.03,2.1,-.55]}><coneGeometry args={[.26,.34,32,1,true]}/><meshStandardMaterial color="#d4c7b6" side={T.DoubleSide} roughness={.65}/></mesh><pointLight position={[-2.03,1.96,-.55]} intensity={2} distance={3} color="#ffc58c"/>
 <Block size={[1.3,.40,.84]} position={[1.85,.20,1.3]} color="#263342" radius={.12} roughness={.9}/><Block size={[1.28,.38,.16]} position={[1.85,.50,1.66]} color="#263342" radius={.065} roughness={.9}/>
</>;


