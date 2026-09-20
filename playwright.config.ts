import {defineConfig, devices} from "@playwright/test";
import os from "node:os";
import path from "node:path";

const dataDir = path.join(os.tmpdir(), `learn-agent-browser-${process.pid}`);

export default defineConfig({
    testDir: "./e2e",
    timeout: 180_000,
    expect: {timeout: 15_000},
    fullyParallel: false,
    workers: 1,
    forbidOnly: Boolean(process.env.CI),
    retries: process.env.CI ? 1 : 0,
    reporter: [["list"], ["html", {open: "never"}]],
    use: {
        ...devices["Desktop Chrome"],
        baseURL: "http://127.0.0.1:1420",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
    webServer: [
        {
            command: "node scripts/browser-test-model.mjs",
            url: "http://127.0.0.1:19090/health",
            timeout: 120_000,
            reuseExistingServer: false,
        },
        {
            command: "node scripts/maven.mjs quarkus:dev",
            url: "http://127.0.0.1:18080/health",
            timeout: 120_000,
            reuseExistingServer: false,
            env: {
                ...process.env,
                LEARN_AGENT_DATA_DIR: dataDir,
                OPENAI_API_KEY: "browser-test",
                OPENAI_MODEL: "browser-test",
                OPENAI_BASE_URL: "http://127.0.0.1:19090/v1",
            },
        },
        {
            command: "pnpm dev:web",
            url: "http://127.0.0.1:1420",
            timeout: 120_000,
            reuseExistingServer: false,
        },
    ],
});
