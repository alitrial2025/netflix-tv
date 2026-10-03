import { useCurrentFrame, useVideoConfig, interpolate, Easing } from "remotion";
import { loadFont } from "@remotion/google-fonts/Manrope";
const { fontFamily } = loadFont("normal", {
  weights: ["400", "500", "600", "700", "800"],
  subsets: ["latin"],
});
export const Copy = ({
  eyebrow,
  headline,
  subline,
  at = 0,
  side = "left",
}: {
  eyebrow: string;
  headline: string;
  subline?: string;
  at?: number;
  side?: "left" | "right";
}) => {
  const f = useCurrentFrame();
  const { width } = useVideoConfig();
  const s = width / 1920;
  return (
    <div
      style={{
        position: "absolute",
        top: 154 * s,
        left: side === "left" ? 112 * s : 1120 * s,
        width: side === "left" ? 860 * s : 700 * s,
        color: "#f2f7fc",
        fontFamily,
        opacity: interpolate(f, [at, at + 24], [0, 1], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        }),
        translate: `0 ${interpolate(f, [at, at + 30], [30 * s, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: Easing.bezier(0.16, 1, 0.3, 1) })}px`,
      }}
    >
      <div
        style={{
          fontSize: 23 * s,
          fontWeight: 700,
          letterSpacing: 5 * s,
          color: "#82d7ff",
          marginBottom: 24 * s,
        }}
      >
        {eyebrow}
      </div>
      <div
        style={{
          fontSize: 91 * s,
          fontWeight: 600,
          letterSpacing: -4 * s,
          lineHeight: 1.04,
          whiteSpace: "pre-line",
        }}
      >
        {headline}
      </div>
      {subline && (
        <div
          style={{
            fontSize: 29 * s,
            lineHeight: 1.45,
            fontWeight: 400,
            color: "#bdcad9",
            marginTop: 25 * s,
          }}
        >
          {subline}
        </div>
      )}
    </div>
  );
};
export const Wordmark = () => {
  const { width } = useVideoConfig();
  const s = width / 1920;
  return (
    <div
      style={{
        fontFamily,
        display: "flex",
        alignItems: "center",
        gap: 18 * s,
        color: "#f2f7fc",
        fontSize: 58 * s,
        fontWeight: 800,
        letterSpacing: -2 * s,
      }}
    >
      <svg width={60 * s} height={60 * s} viewBox="0 0 64 64">
        <rect width="64" height="64" rx="15" fill="#1761b0" />
        <path d="M25 18L46 32L25 46Z" fill="white" />
        <circle cx="51" cy="13" r="4" fill="#82d7ff" />
      </svg>
      NetflixPro
    </div>
  );
};
export const Font = { fontFamily };
