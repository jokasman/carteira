# Carteira (Android)

Aplicação nativa Android de finanças pessoais, construída com Kotlin, Jetpack Compose e Room. Os dados são guardados localmente no dispositivo.

## Funcionalidades

- Painel com saldo, receitas, despesas, contas e movimentos recentes.
- Registo de despesas, receitas e transferências, com conta, nota e recorrência.
- Gestão de várias contas e carteiras.
- Orçamentos mensais, histórico de limites e associação rápida de movimentos.
- Relatórios, filtros e ordenação de movimentos.
- Importação de extratos CSV, deteção de duplicados e regras para associar orçamentos.
- Envio de movimentos selecionados para classificação e liquidação por outra pessoa.
- Interface em português e valores em euros.

## Classificar movimentos com outra pessoa

Em **Movimentos**, mantém premido um movimento e seleciona os restantes que queres enviar. Toca no ícone de partilha e guarda o pedido JSON no dispositivo; a app abre a página de classificação. O pedido inclui apenas os movimentos selecionados que sejam elegíveis. Para cada despesa, só seguem os orçamentos disponíveis no mês respetivo. Para rever uma liquidação existente, seleciona a receita e todas as despesas já associadas.

O remetente abre [Classificar e liquidar](https://jokasman.github.io/carteira/classificacao/), carrega localmente o pedido e cria uma ligação para enviar. A outra pessoa abre essa ligação e atribui orçamentos e/ou liquida receitas sem carregar ou descarregar ficheiros. Para liquidar uma receita, as despesas incluídas têm de somar exatamente o mesmo valor. No fim, partilha a ligação de resposta com o remetente. O remetente abre essa ligação, descarrega o JSON e, na Carteira, toca em **Resposta**, escolhe o ficheiro, revê as decisões e toca em **Aplicar decisões**.

### Privacidade

O pedido contém somente os movimentos selecionados e os campos necessários à decisão. Não inclui a base de dados, contas ou notas. A página é estática e lê o pedido localmente no browser; os dados seguem embutidos no fragmento da ligação, que não é enviado ao site. Qualquer pessoa com a ligação consegue ver os movimentos incluídos. Não existe backend nem armazenamento partilhado. A ligação de resposta contém apenas o identificador do pedido e as escolhas. A app guarda pedidos pendentes localmente por até 30 dias, exclui-os das cópias de segurança Android e valida novamente cada escolha antes de importar.

### Publicação

O formulário está publicado em [jokasman.github.io/carteira/classificacao](https://jokasman.github.io/carteira/classificacao/). O GitHub Pages publica a pasta `docs` da branch `main` e atualiza o site após os pushes. O ficheiro-fonte está em `docs/classificacao/index.html`.

## Abrir e executar

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e execute a configuração `app` num dispositivo/emulador Android 8.0 ou posterior. O projeto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Compose e Room.

## APK e instalação

O `applicationId` atual é `pt.jokasman.carteira`. Mantém este identificador para que o Android reconheça novas versões como atualizações da mesma app. A instalação antiga com `pt.carteira.app` continua separada e os dados locais não migram automaticamente.

O workflow **Build Android APK** compila em cada push para `main`, assina com a chave guardada em GitHub Secrets e publica o artefacto `Carteira-APK` quando o build tem sucesso. Se os secrets não estiverem disponíveis, o workflow não publica o APK. Nunca guardar a chave ou a palavra-passe no repositório. Os APKs não devem ser adicionados ao Git.

## Estado e decisões

Para o estado atual, os resultados de build e as instruções de continuidade, consulta [docs/estado-atual.md](docs/estado-atual.md). A importação bancária atual continua a ser por CSV; a integração Enable Banking/Firebase foi abandonada.

Os movimentos permanecem no dispositivo. A classificação partilhada usa ligações com o pedido e a resposta codificados no fragmento do URL; não sincroniza contas nem dá à outra pessoa acesso à base de dados.
