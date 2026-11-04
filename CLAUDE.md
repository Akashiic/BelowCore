# Instruções para o Claude

## Idioma
- **Responda sempre em português do Brasil (pt-BR)**, em todas as mensagens ao usuário, inclusive relatórios,
  resumos e perguntas. Isso vale para qualquer sessão neste repositório.
- Código, identificadores e mensagens de commit continuam em inglês (padrão do projeto); comentários seguem o
  estilo do código ao redor.

## Projeto
- Akashic Bellow: camadas negativas (Y < 0) para Minecraft 1.7.10 / Forge 10.13.4.1614, pensado para o modpack
  TEOF 4.1 (servidor Crucible). Arquitetura e decisões em `docs/DESIGN.md`; uso em `README.md`.
- Não é um CubicChunks: piso fixo por mundo, coluna vanilla intocada, seções profundas em array separado.
- Política (desde a beta.4): o BelowCore é **só o core** e não gera conteúdo abaixo de Y 0. Preenchimento, piso,
  minérios e cavernas são dos geradores registrados na API (no TEOF, o VitaGenesis). O núcleo só cria as seções
  vazias, troca a bedrock legada onde Y −1 ficou sólido e, sem nenhum gerador, põe a camada provisória (pedra +
  bedrock, substituída por retrogen). Não reintroduza conteúdo no core.
- Prioridade: qualidade técnica e segurança acima de velocidade; testar com evidência antes de afirmar.
- O trabalho vai para a branch `main` (pedido do usuário).

## Build e testes
- `VERSION=0.1.0-beta.6 ./gradlew build` (não há tag git).
- `tools/selftest.sh [-Pbellow.compatRun=true]` (servidor, 3 fases) e `tools/clienttest.sh` (cliente headless).
- Sonda de compatibilidade com mods reais: fases `compat`, `compatverify`, `compatrecheck` e `bench`
  (ver README e `docs/DESIGN.md` §10).
