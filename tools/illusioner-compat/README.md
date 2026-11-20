# Совместимость The Illusioner и The Shooting Star

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

Поставляемый агент находится в `config/lanminecraft/illusioner-shooting-star-compat.jar`.
Его подключает `launcher/jvm-args.txt`. `tools/modified-lock.json` закрепляет
SHA256 обоих исходных модов, агента и целевую платформу. Агент дополнительно
проверяет SHA256 класса Mixin 0.8.5 и сигнатуру, аннотацию и инструкции
обработчика Shooting Star. При смене любого входа повторить проверку.

## Сборка и проверка

Собрать агент с JDK 25, используя JAR `asm` и `asm-tree` 9.10.1 из файлов,
указанных в `tools/experimental-warnings/build-inputs.json`.

```powershell
python tools/illusioner-compat/build.py --out $agent --asm $asm --tree $tree --javac $jdk25Javac
```

Независимая проверка загружает оригинальные классы обоих модов, официальный
клиент после Forge 47.4.29 и штатный `mixin-0.8.5.jar`. Она сверяет целевой
вызов и остальные обработчики и исполняет четыре комбинации состояний боя
и ролика на Java 17 с `-Xverify:all`.

```powershell
$probeClasspath = "$agent;$asm;$tree;$asmCommons"
& $jdk25Javac --release 17 -classpath $probeClasspath -d $probeClasses `
  tools/illusioner-compat/IllusionerShootingStarProbe.java
& $java17 -Xverify:all "-javaagent:$agent" -classpath "$probeClasses;$probeClasspath" `
  dev.lanminecraft.pack.IllusionerShootingStarProbe $shootingStar $illusioner $patchedForgeClient $mixin
```

В журнале игры подтверждением установки обработчика служит строка
`Composed the Shooting Star and Illusioner respawn screen handlers`.
