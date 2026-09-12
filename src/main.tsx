import {StrictMode} from "react";
import {createRoot} from "react-dom/client";
import EditorWorker from "../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker";
import JsonWorker from "../node_modules/monaco-editor/esm/vs/language/json/json.worker.js?worker";
import CssWorker from "../node_modules/monaco-editor/esm/vs/language/css/css.worker.js?worker";
import HtmlWorker from "../node_modules/monaco-editor/esm/vs/language/html/html.worker.js?worker";
import TsWorker from "../node_modules/monaco-editor/esm/vs/language/typescript/ts.worker.js?worker";
import * as monaco from "monaco-editor";
import {loader} from "@monaco-editor/react";
import App from "./App";
import "./styles.css";

(self as typeof self & {
    MonacoEnvironment: {getWorker: (_moduleId: string, label: string) => Worker};
}).MonacoEnvironment = {
    getWorker: (_moduleId, label) => {
        if (label === "json") return new JsonWorker();
        if (label === "css" || label === "scss" || label === "less") return new CssWorker();
        if (label === "html" || label === "handlebars" || label === "razor") return new HtmlWorker();
        if (label === "typescript" || label === "javascript") return new TsWorker();
        return new EditorWorker();
    },
};

loader.config({monaco});

createRoot(document.getElementById("root")!).render(
    <StrictMode>
        <App/>
    </StrictMode>,
);
