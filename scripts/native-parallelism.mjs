import os from "node:os";

export function withNativeParallelism(args) {
    if (!args.includes("-Pnative") || args.some((arg) => arg.startsWith("-Dnative.image.parallelism="))) {
        return args;
    }
    return [...args, `-Dnative.image.parallelism=${Math.max(1, os.cpus().length - 3)}`];
}
