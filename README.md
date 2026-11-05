# Carteira (Android)

Aplicação nativa Android de finanças pessoais, construída com Kotlin, Jetpack Compose e Room. Os dados são guardados localmente no dispositivo.

## Funcionalidades

- Painel com saldo, receitas, despesas, contas e movimentos recentes.
- Registo de despesas, receitas e transferências, com categoria, conta, nota e indicação de recorrência.
- Gestão de várias contas e carteiras.
- Orçamentos por categoria com acompanhamento do valor gasto e disponível.
- Relatórios de receitas, despesas e distribuição por categoria.
- Filtro por tipo de movimento e remoção de movimentos por toque prolongado.
- Interface em português e valores formatados em euros.

## Abrir e executar

Abra esta pasta no Android Studio (Hedgehog ou posterior), aguarde a sincronização do Gradle e execute a configuração `app` num dispositivo/emulador Android 8.0 ou posterior. O projeto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Compose e Room.

## Limites desta versão

Os dados permanecem no dispositivo. Sincronização bancária, conta online, partilha entre pessoas e exportação/importação requerem serviços e fluxos adicionais.
