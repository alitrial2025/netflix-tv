import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { Stage } from "./Stage";
import { Camera, Room, Television, smooth, lerp3 } from "./world";
import { Copy } from "./Type";
export const TVDive = () => {
  const f = useCurrentFrame();
  return (
    <AbsoluteFill>
      <Stage room>
        <Camera
          position={lerp3([1.5, 1.55, 3.15], [0, 1.28, 1.69], smooth(f / 160))}
          target={[0, 1.28, 0.06]}
          fov={38}
        />
        <Room />
        <group position={[0, 1.28, 0.02]}>
          <Television on frame={f} />
        </group>
      </Stage>
      {f < 66 && (
        <AbsoluteFill
          style={{
            opacity: interpolate(f, [48, 66], [1, 0], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
            }),
          }}
        >
          <AbsoluteFill
            style={{
              background:
                "linear-gradient(90deg,rgba(8,14,23,.88),rgba(8,14,23,.64) 34%,transparent 65%)",
            }}
          />
          <Copy
            eyebrow="NETFLIXPRO FOR TV"
            headline={"Made for\nthe big screen."}
          />
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
