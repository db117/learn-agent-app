# Language Pack

## 定位

`LanguagePack` 是产品级 Plugin；`Skill` 是 Agent Capability。

## 核心接口

```java
public interface LanguagePack {
    String id();
    LanguageMetadata metadata();
    Toolchain toolchain();
    WorkspaceTemplateProvider templates();
}
```

PracticeGenerator 与 AssessmentStrategy 是按 language pack 选择的可选能力，不属于上述核心 SPI；它们在 Practice/Learn
阶段单独注册和解析。Step 4 不实现这两类能力的行为。

## 第一实现

```text
TypeScriptLanguagePack
```

目录：

```text
backend/src/main/resources/language-packs/typescript/
  pack.yaml
  knowledge/
  skills/
  templates/
  exercises/
```

`pack.yaml` 示例：

```yaml
id: typescript
displayName: TypeScript

toolchain:
  runtime: node
  packageManager: pnpm
  compiler: tsc
  testRunner: vitest

fileExtensions:
  - ts
  - tsx
```

未来增加 Rust/Python/Java 时，不应修改 TutorAgent、Practice Engine、Learning Engine 的核心边界。
