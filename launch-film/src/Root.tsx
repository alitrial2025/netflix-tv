import {AbsoluteFill,Composition,Folder,Series,staticFile} from 'remotion';
import {Audio} from '@remotion/media';
import {TVSetup} from './TVSetup';
import {RemoteReveal} from './RemoteReveal';
import {TVDive} from './TVDive';
import {PhoneDrop} from './PhoneDrop';
import {PhoneHero} from './PhoneHero';
import {Finale} from './Finale';
export const LaunchFilm=()=><AbsoluteFill style={{background:'#080e17'}}><Series>
 <Series.Sequence name="The setup — hands, breath, walk" durationInFrames={210} premountFor={30}><TVSetup/></Series.Sequence>
 <Series.Sequence name="The remote" durationInFrames={120} premountFor={30}><RemoteReveal/></Series.Sequence>
 <Series.Sequence name="Enter the TV — native Kotlin" durationInFrames={210} premountFor={30}><TVDive/></Series.Sequence>
 <Series.Sequence name="The catch" durationInFrames={120} premountFor={30}><PhoneDrop/></Series.Sequence>
 <Series.Sequence name="The mobile — native Kotlin" durationInFrames={180} premountFor={30}><PhoneHero/></Series.Sequence>
 <Series.Sequence name="Released — download" durationInFrames={240} premountFor={30}><Finale/></Series.Sequence>
 </Series><Audio src={staticFile('score.wav')} volume={1} premountFor={30}/></AbsoluteFill>;
export const RemotionRoot=()=><><Composition id="NetflixProLaunch4K" component={LaunchFilm} width={3840} height={2160} fps={30} durationInFrames={1080}/><Composition id="NetflixProLaunch1080" component={LaunchFilm} width={1920} height={1080} fps={30} durationInFrames={1080}/><Folder name="Scenes">
 <Composition id="TVSetup" component={TVSetup} width={1920} height={1080} fps={30} durationInFrames={210}/>
 <Composition id="RemoteReveal" component={RemoteReveal} width={1920} height={1080} fps={30} durationInFrames={120}/>
 <Composition id="TVDive" component={TVDive} width={1920} height={1080} fps={30} durationInFrames={210}/>
 <Composition id="PhoneDrop" component={PhoneDrop} width={1920} height={1080} fps={30} durationInFrames={120}/>
 <Composition id="PhoneHero" component={PhoneHero} width={1920} height={1080} fps={30} durationInFrames={180}/>
 <Composition id="Finale" component={Finale} width={1920} height={1080} fps={30} durationInFrames={240}/>
 </Folder></>;


