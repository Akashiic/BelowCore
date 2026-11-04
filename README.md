# Below Core

Núcleo das camadas negativas do mundo (Y < 0) para **Minecraft 1.7.10** (Forge 10.13.4.1614): armazenamento, motor,
rede, luz, compatibilidade e a API para geradores. O BelowCore **não gera conteúdo** abaixo de 0: o que a camada
profunda é (deepslate, piso de bedrock, minérios, cavernas) vem de um gerador registrado na API; no TEOF, do
**VitaGenesis**.

Status: **beta** (`0.1.0-beta.7`). Veja os riscos abertos em [docs/DESIGN.md §11](docs/DESIGN.md#11-riscos-abertos-beta).

## O que faz

* Estende o mundo para baixo até um piso fixo por dimensão (padrão **−64**, configurável de −16 a **−512**).
  O teto continua 256 e nada acima de Y 0 muda de forma.
* Entrega aos geradores cada chunk novo **e** cada chunk salvo antes da instalação (retrogen no carregamento), com as
  seções abaixo de 0 vazias, e depois abre a bedrock antiga (Y 0..4) onde a camada ficou sólida.
* Dá aos geradores a região 3×3, as primitivas de veio da 1.18 (`OreFeature`, com as variantes deepslate do Et Futurum
  Requiem), a etapa de luz e as etapas depois do tick (API, abaixo).
* Sem nenhum gerador, uma camada provisória de pedra com piso de bedrock (sem minérios nem cavernas), para que nada
  abaixo de 0 seja vazio; o primeiro gerador instalado depois a substitui.

O desenho, as decisões e o motivo de não usar CubicChunks estão em [docs/DESIGN.md](docs/DESIGN.md).

## Requisitos

| | versão |
|---|---|
| Forge | 10.13.4.1614 |
| UniMixins | 0.3.1+ (Mixin 0.8.7, MixinExtras 0.5) |
| GTNHLib | 0.11.40+ (TEOF 4.1 usa 0.11.51) |
| ChunkAPI | 0.8.4+ (obrigatório) |

Servidores: Forge e **Crucible** (testado com o build de produção 1.7.10-staging-67b53034; o Bellow corrige o Y que o
CraftBukkit entregava aos plugins). Para WorldEdit, WorldGuard e GriefPreventionPlus abaixo de 0, veja a seção
"Plugins no Crucible" abaixo. Qualquer servidor com CraftBukkit (`v1_7_R4`) recebe as variantes do Crucible; outros
forks (Thermos, Cauldron, KCauldron) não foram testados e, se o código deles diferir, o servidor recusa subir com
um erro de mixin, sem tocar no mundo. Pelo mesmo motivo, as integrações com IC2 e Galacticraft exigem as versões do
pack (IC2 2.2.828, Galacticraft 4.0): outra versão com o código mudado para o boot.

Opcionais com integração: Et Futurum Requiem, EndlessIDs, ArchaicFix (Phosphor), Hodgepodge, Angelica,
IndustrialCraft 2 (Minerador e Magnetizador alcançam a camada profunda) e Galacticraft (foguetes não são destruídos
abaixo de Y 0). Testado no servidor com IC2, Immersive Engineering e Galacticraft: veja `docs/DESIGN.md` §9–§10.
O mod é obrigatório **no servidor e no cliente**.

## Plugins no Crucible (WorldEdit, WorldGuard, GriefPreventionPlus)

* **WorldEdit 6.1.9**: adicione o agent à linha de comando do servidor, junto dos outros agents
  (`-javaagent:AkashicBellow-WorldEdit-6.1.9-<versão>-agent.jar`; build: `./gradlew -p agent build`). Ele só altera o
  WorldEdit 6.1.9 exato (SHA-256 de cada classe) e não toca em nenhum arquivo; no log aparece
  `[AkashicBellow-WE] WorldEdit 6.1.9 verified`. Sem ele, seleções e edições param em Y 0.
* **GriefPreventionPlus**: `GriefPrevention.Claims.MaximumDepth: -64` (o piso) em `GriefPreventionData/config.yml`;
  com 0 os claims não protegem nada abaixo de Y 0.
* **WorldGuard**: regiões antigas que vão de 0 ao topo não cobrem a camada nova. `tools/wgdeep.py <servidor>` lista
  essas regiões (só leitura) com os comandos para estendê-las (`//pos1`, `//pos2`, `/rg redefine`).

Detalhes em `docs/DESIGN.md` §9.1.

## Configuração (`config/akashicbellow.cfg`)

| opção | padrão | |
|---|---|---|
| `general.minY` | −64 | piso (múltiplo de 16). **Travado por mundo** no primeiro uso em `<mundo>/akashicbellow.dat`. Custo medido: +46% de RAM por chunk em −64, 5× em −512 |
| `general.dimensions` | `0` | dimensões com camada profunda |
| `general.spawnMobsInDeep` | true | spawn natural também abaixo de 0 |
| `worldgen.enabled` | true | rodar as etapas de geração (os geradores, a luz) |
| `worldgen.retrogen` | true | entregar aos geradores os chunks salvos antes de terem camada |
| `worldgen.replaceLegacyBedrock` / `legacyBedrockMaxY` | true / 4 | trocar a bedrock antiga (Y 0..4) por deepslate do EFR (ou pedra), onde a camada ficou sólida em Y −1 |
| `worldgen.provisionalLayer` | true | sem nenhum gerador: camada provisória de pedra com piso de bedrock (substituída quando um gerador for instalado) |
| `worldgen.stageBudgetMillis` | 10 | tempo por tick do mundo para a luz e os callbacks depois do tick |
| `worldgen.strictGenerators` | false | testar geradores: recusa escritas pelo `World` que o contrato proíbe |

Bloco de preenchimento, minérios e cavernas são configurados no gerador (VitaGenesis: categoria `layer`). As chaves
`worldgen.deepBlock`, `worldgen.ores`, `worldgen.scaleBottomAnchoredCounts` e `worldgen.oreCountMultiplier` das
versões anteriores não são mais lidas.

## Instalação num servidor existente

1. **Backup do mundo.**
2. Instale o jar no servidor e em todos os clientes; ajuste `minY` antes do primeiro boot (depois ele fica travado).
3. Instale um gerador (VitaGenesis). Os chunks antigos recebem o subsolo à medida que são carregados (não há pré-geração; um pré-gerador comum
   que carregue os chunks também dispara o retrogen). `/bellow info` mostra o piso e o estado de geração do chunk atual,
   `/bellow stats [raio]` o histograma dos blocos abaixo de 0.

Comandos (op): `/bellow info`, `/bellow stats [raio]`, `/bellow selftest`.

Desinstalar: o mundo continua abrindo, mas tudo abaixo de Y 0 é perdido.

## Downloads (GitHub Actions)

Cada push no `main` roda o workflow **Build**, que gera do mesmo commit o mod e o agent do WorldEdit (companion) e
publica os dois como artifacts do mesmo run, sem zip: `AkashicBellow-<versão>.jar` e
`AkashicBellow-WorldEdit-6.1.9-<versão>-agent.jar`, com `<versão>` = `0.1.0-beta.7-<commit>`. O resumo do run lista o
SHA-256 de cada jar.

## Compilar e testar

```bash
./gradlew build                      # jar em build/libs (defina VERSION=... se não houver tag git)
./gradlew test                       # testes unitários
tools/selftest.sh                    # servidor, 3 fases (cria → gera/verifica → relê do disco numa JVM nova)
tools/selftest.sh -Pbellow.compatRun=true          # idem com EFR + EndlessIDs + ArchaicFix + Hodgepodge
tools/clienttest.sh                                # cliente headless (xvfb), renderer vanilla
tools/clienttest.sh -Pbellow.compatRun=true -Pbellow.angelica=true   # cliente com Angelica
tools/crucibletest.sh <cópia do servidor> [fase]   # Crucible real; apaga world/, só roda numa cópia com .bellow-test-copy
./gradlew -p agent build             # agent do WorldEdit em agent/build/libs
./gradlew -p agent test -Pbellow.weJar=<worldedit-bukkit-6.1.9.jar> -Pbellow.serverJar=<server.jar do Crucible>
tools/wgdeep.py <servidor> [--world world] [--floor -64]   # regiões do WorldGuard que param em Y 0 (só leitura)
```

Num servidor com os mods do pack (jar reobfuscado em `mods/`), adicione
`-Dakashicbellow.selftest=true -Dakashicbellow.selftest.exit=true -Dakashicbellow.selftest.phase=<fase>`:
`compat` e depois `compatverify` (sonda de compatibilidade), `bench` (custo por chunk) ou `chunkload` (chunks forçados
por ticket, sem jogadores: TileEntity, ticks aleatórios e entidades abaixo de 0), num mundo novo.

O teste de cliente precisa de `xvfb-run` e EGL/Mesa (`libegl1 libegl-mesa0 libgles2`).
Os relatórios ficam em `run/server/akashicbellow-selftest-*.txt` e `run/client/akashicbellow-clienttest.txt`;
os screenshots em `run/client/screenshots/`.

## API

`com.akashiic.bellow.api.BellowAPI` (versão da API 7, em `BellowAPI.apiVersion()`):

* Leitura: `getMinY(world)`, `hasDeepLayers(world)`, `getDeepSection(chunk, sectionY)`, para mods que iteram seções
  diretamente e querem enxergar o subsolo. `IDeepChunk`/`IDeepWorld` são somente leitura.
* **Geradores profundos** (cavernas, aquíferos, estruturas): `registerGenerator(id, IDeepGenerator[, prioridade])`
  até o postInit; ordem por prioridade e depois id, gravada por dimensão em `akashicbellow.dat` (aviso quando muda).
  O gerador recebe cada chunk novo com as seções profundas vazias (`generateTerrain`), a região 3×3 quando os
  vizinhos têm terreno (`decorate(IDeepRegion)`, com `setBlock`, `placeOre`, `placeBlob`, `genStateAt`), e,
  depois do tick do mundo, o chunk decorado (`onDecorated`) e o chunk já iluminado pela etapa de luz do Bellow com o
  5×5 em volta decorado (`onLit`). Cada etapa roda uma vez por chunk; o que ficou pendente (chunk descarregado,
  servidor parado) roda na próxima carga. Chunks cujo subsolo foi gerado antes de o gerador ser instalado nunca são
  entregues a ele. `appliesTo(world)` e `version()` opcionais. Contrato completo no javadoc de `IDeepGenerator` e em
  [docs/DESIGN.md §5.1](docs/DESIGN.md#51-geradores-profundos-api).
* Por chunk e por gerador: `getGeneratorStages(chunk, id)` (o que o gerador já fez ali) e `getGeneratorData(chunk,
  id)` (NBT do gerador, salvo com o subsolo e mantido sem o addon); `isDecorated(chunk)`, `isLightPending(chunk)`.
* API 4: o núcleo não preenche a camada; os geradores recebem as seções vazias. Saíram `getFillBlock`, `getFillMeta`,
  `isDeepFill`, `isBaseStone` e `isDeepHost` (da API 3).
* **Dimensão de um mod** (API 5): `claimDimension(dimensionId, minY, generatorId)` até o postInit. A dimensão ganha
  camada profunda com esse piso, sem precisar estar em `general.dimensions` e sem usar `general.minY`; só o gerador
  indicado roda nela (nenhum outro é consultado, nem o `appliesTo`), e um gerador que reclamou dimensões só roda
  nelas. Um save que já travou outro piso para a dimensão mantém o dele (com aviso). Reclamar a mesma dimensão com
  outro gerador ou outro piso é erro na inicialização; a mesma chamada duas vezes é aceita.
  `getDimensionOwner(dimensionId)` diz quem reclamou (também no `/bellow info`).
  API 6: o dono escolhe o que substitui a bedrock legada em Y 0..4 na dimensão dele
  (`IDeepGenerator.legacyBedrockReplacement`/`legacyBedrockReplacementMeta`; `null` = padrão, `bedrock` = manter;
  bloco opaco inteiro, sem luz e sem tile entity). API 7: também onde Y −1 ficou aberto (se o terreno do dono deu
  certo no chunk; ali `null` mantém a bedrock) e com ar ou fonte de fluido, acesos pelo `World` na etapa de luz.
* `resendDeepSections(world, chunk, slotMask)`: reenvia seções escritas diretamente aos jogadores que já receberam o
  chunk.
* Para testar um gerador: `worldgen.strictGenerators=true` recusa (e registra) escritas pelo `World` que o contrato
  proíbe durante `generateTerrain`/`decorate`; num self-test sem ticks, `runDeepStages(world)` roda na hora as etapas
  de depois do tick.

Para depender da API, declare `required-after:akashicbellow` e confira `BellowAPI.apiVersion() >= 7` (ou a versão de
que precisa) em tempo de execução, dentro de um `try`/`catch (NoSuchMethodError e)` (a API 1 não tem o método). Não compare a versão do mod:
os builds de CI levam o sufixo do commit.
