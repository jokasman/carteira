# Carteira (Android)

Aplicação nativa Android de finanças pessoais, construída com Kotlin, Jetpack Compose e Room. Os dados são guardados localmente no dispositivo.

## Funcionalidades

- Painel com saldo, receitas, despesas, contas e movimentos recentes.
- Registo de despesas, receitas e transferências, com categoria, conta, nota e indicação de recorrência.
- Gestão de várias contas e carteiras.
- Orçamentos por categoria com acompanhamento do valor gasto e disponível.
- Detalhe de cada orçamento com os movimentos do mês e criação de movimentos já associados ao orçamento.
- Relatórios de receitas, despesas e distribuição por categoria.
- Filtro por tipo de movimento e remoção de movimentos por toque prolongado.
- Envio de movimentos selecionados para atribuição de orçamentos e liquidação de receitas num formulário web.
- Interface em português e valores formatados em euros.

## Classificar movimentos com outra pessoa

Na secção **Movimentos**, mantém premidos os movimentos pretendidos e toca no ícone de partilha. O pedido contém apenas movimentos selecionados que sejam elegíveis para classificação ou liquidação. Para cada despesa, inclui só os orçamentos disponíveis no mês respetivo. Para rever uma liquidação existente, seleciona a receita e todas as despesas já associadas.

A outra pessoa abre `https://jokasman.github.io/carteira/classificacao/`, seleciona o pedido e atribui orçamentos e/ou liquida receitas. Uma liquidação só é aceite quando as despesas somam exatamente o valor da receita, conforme as regras da app. Em Android, **Enviar resposta** abre a partilha do sistema para escolher WhatsApp; noutros browsers, descarrega a resposta JSON. Na Carteira, toca em **Resposta**, escolhe o ficheiro e confirma as alterações apresentadas.

A página é estática: lê no browser apenas o ficheiro enviado e não consulta nem recebe outros movimentos. A resposta contém apenas o identificador do pedido e as escolhas. A app guarda localmente os pedidos pendentes por até 30 dias, exclui-os das cópias de segurança Android e valida novamente cada movimento, orçamento e liquidação antes de importar.

Para publicar a página, configura GitHub Pages no repositório: **Settings → Pages → Deploy from a branch → `main` → `/docs`**. O ficheiro da página está em `docs/classificacao/index.html`.

## Abrir e executar

Abra esta pasta no Android Studio (Hedgehog ou posterior), aguarde a sincronização do Gradle e execute a configuração `app` num dispositivo/emulador Android 8.0 ou posterior. O projeto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Compose e Room.

O workflow **Build Android APK** compila em cada push para `main` e pode ser iniciado manualmente no separador **Actions** do GitHub. Para disponibilizar APKs instaláveis e atualizáveis, configura os secrets de repositório `CARTEIRA_KEYSTORE_BASE64` e `CARTEIRA_KEYSTORE_PASSWORD` com um keystore PKCS#12 permanente (alias `carteira`). Sem esses secrets, o workflow só compila e não publica um APK, para não distribuir um APK de depuração com assinatura temporária.

Com a assinatura permanente configurada, descarrega o artifact `Carteira-APK` na página da execução. O identificador Android foi alterado para `pt.jokasman.carteira` para evitar o conflito de instalação mostrado pela versão anterior. A instalação antiga (`pt.carteira.app`) permanece separada e os dados locais não são migrados automaticamente para a nova aplicação.

## Limites desta versão

Os dados dos movimentos permanecem no dispositivo. A classificação por outra pessoa usa ficheiros locais de pedido e resposta, sem sincronização de contas ou acesso da outra pessoa à base de dados.
