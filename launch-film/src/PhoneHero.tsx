import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Stage } from "./Stage";
import { Camera, Phone, Floor, smooth, mix } from "./world";
import { Copy } from "./Type";
export const PhoneHero = () => {
  const f = useCurrentFrame(),
    p = smooth(f / 179);
  return (
    <AbsoluteFill>
      <Stage>
        <Camera position={[0, 1.45, 3.5]} target={[0, 1.25, 0]} fov={36} />
        <Floor />
        <group
          position={[-0.58, 1.23, 0.3]}
          rotation={[mix(0.08, 0, p), mix(-0.25, 0.12, p), -0.07]}
          scale={1.75}
        >
          <Phone on frame={f < 90 ? f : f - 90} details={f >= 90} />
        </group>
      </Stage>
      <Copy
        side="right"
        eyebrow="NETFLIXPRO FOR ANDROID"
        headline={"Take the story\nwith you."}
        subline="Browse. Discover. Make it your evening."
        at={10}
      />
    </AbsoluteFill>
  );
};
