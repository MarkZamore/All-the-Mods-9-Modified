# Совместимость клиента Forge

## The Illusioner и The Shooting Star

В Minecraft 1.20.1 The Illusioner 1.1.4 и The Shooting Star [Demo] 1.5.1
перехватывают первый вызов `Minecraft.setScreen` из `ClientPacketListener.handleRespawn`.
Mixin 0.8.5 отклоняет второй `@Redirect` с ошибкой `0/1 succeeded`. В официальных
версиях Shooting Star 1.5.2 и 1.6.0 этот перехват сохранён.

Агент изменяет один обработчик Shooting Star в памяти через точный
`MixinInfo.loadMixinClass`. `@ModifyArg` передаёт `null` при активном ролике
Shooting Star и исходный экран в остальных случаях. Штатный `@Redirect`
Illusioner пропускает вызов во время его боя. Экран определяется по типу
`Screen`, который присутствует в единственном экземпляре в исходном вызове и
в обработчике Illusioner. Остальные перехваты и исходные JAR модов сохраняются.

## Lucent

Lucent 1.5.5 запускает обработчик освещения после каждого успешного
`Level.setBlock`. Во встроенном сервере этот перехват срабатывает и на
`ServerLevel`. При перезагрузке шейдера серверный вызов достигал
`SodiumWorldRenderer.scheduleRebuildForChunk` во время пересоздания секций.

Агент проверяет исходную сигнатуру, аннотацию и инструкции перехвата Lucent.
Клиентская проверка использует штатный `Level.isClientSide()`. После успешной
клиентской записи выполняется исходный расчёт динамического света.
На точном Forge 47.4.29 отдельный и пакетный сетевые апдейты блоков
передаются клиентскому потоку и доходят от `ClientLevel` до того же метода
`Level.setBlock`. Исходный JAR Lucent сохраняет SHA256
`dc5fd7883dabcdaf3bf5ba02d8f20f2974df20edc6ce7a05e788d998fd3ef152`.

Поставляемый агент находится в `config/lanminecraft/forge-client-compat.jar`.
Его подключает `launcher/jvm-args.txt`. `tools/modified-lock.json` закрепляет
SHA256 исходных модов, агента и целевую платформу. При запуске агент проверяет
SHA256 класса Mixin 0.8.5 и семантику двух целевых обработчиков. При смене
любого входа повторить проверку.

## Сборка и проверка

Собрать агент с JDK 25, используя JAR `asm` и `asm-tree` 9.10.1 из файлов,
указанных в `tools/experimental-warnings/build-inputs.json`.

```powershell
python tools/illusioner-compat/build.py --out $agent --asm $asm --tree $tree --javac $jdk25Javac
```

Проверки используют официальный клиент после Forge 47.4.29, штатный
`mixin-0.8.5.jar` и оригинальные JAR модов. Они исполняют четыре
комбинации состояния боя и ролика и четыре комбинации стороны мира и
результата установки блока на Java 17 с `-Xverify:all`.

```powershell
$probeClasspath = "$agent;$asm;$tree;$asmCommons"
& $jdk25Javac --release 17 -classpath $probeClasspath -d $probeClasses `
  tools/illusioner-compat/IllusionerShootingStarProbe.java `
  tools/illusioner-compat/LucentBlockChangeProbe.java
& $java17 -Xverify:all "-javaagent:$agent" -classpath "$probeClasses;$probeClasspath" `
  dev.lanminecraft.pack.IllusionerShootingStarProbe $shootingStar $illusioner $patchedForgeClient $mixin
& $java17 -Xverify:all "-javaagent:$agent" -classpath "$probeClasses;$probeClasspath" `
  dev.lanminecraft.pack.LucentBlockChangeProbe $lucent $patchedForgeClient
```

В журнале игры подтверждением установки обработчика служит строка
`Composed the Shooting Star and Illusioner respawn screen handlers`.
Перехват Lucent подтверждает строка
`Restricted block-change lighting to the client level`.
