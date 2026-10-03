import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Stage } from "./Stage";
import { Camera, Phone, Hand, Limb, Floor, smooth, mix } from "./world";
import { Copy } from "./Type";
export const PhoneDrop = () => {
  const f = useCurrentFrame(),
    fall = Math.max(0, Math.min(1, (f - 8) / 46)) ** 2,
    caught = smooth((f - 54) / 16),
    recover = smooth((f - 70) / 45),
    y = 3.1 - 1.52 * fall - 0.045 * Math.sin(Math.PI * caught);
  return (
    <AbsoluteFill>
      <Stage>
        <Camera position={[1.22, 2.35, 3.5]} target={[0.15, 1.5, 0]} fov={34} />
        <Floor />
        <group
          position={[0.25, 1.13, 0]}
          rotation={[-Math.PI / 2, 0, -0.05]}
          scale={2.1}
        >
          <Hand curl={mix(0.15, 0.65, caught)} />
        </group>
        <Limb
          a={[0.25, 1.105, -0.005]}
          b={[0.25, 0.855, -0.14]}
          r={0.065}
          endRadius={0.068}
        />
        <Limb
          a={[0.25, 0.86, -0.15]}
          b={[0.36, 0.12, -0.61]}
          r={0.1}
          endRadius={0.09}
          color="#263441"
        />
        <group
          position={[0.25, y, 0.06]}
          rotation={[
            mix(-0.35, -0.08, recover),
            mix(-1.3, 0.12, fall),
            mix(0.7, -0.12, fall),
          ]}
        >
          <Phone />
        </group>
      </Stage>
      <Copy
        eyebrow="AND JUST LIKE THAT"
        headline={"A world of stories.\nIn your hand."}
        at={60}
      />
    </AbsoluteFill>
  );
};
