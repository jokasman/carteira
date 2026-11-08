# Carteira (Android)

Aplicação nativa Android de finanças pessoais, construída com Kotlin, Jetpack Compose e Room. Os dados são guardados localmente no dispositivo.

## Funcionalidades

- Painel com saldo, receitas, despesas, contas e movimentos recentes.
- Registo de despesas, receitas e transferências, com conta, nota e recorrência.
- Gestão de várias contas e carteiras.
- Orçamentos mensais e histórico de limites; despesas podem ficar sem orçamento e ser associadas depois.
- Relatórios, filtros e ordenação de movimentos.
- Importação de extratos CSV, deteção de duplicados e regras para associar orçamentos.
- Envio de movimentos selecionados para classificação e liquidação por outra pessoa.
- Interface em português e valores em euros.

## Classificar movimentos com outra pessoa

Em **Movimentos**, mantém premido um movimento e seleciona os restantes que queres enviar. Toca no ícone de partilha e guarda o pedido JSON no dispositivo; a app abre a página de classificação. O pedido inclui apenas os movimentos selecionados que sejam elegíveis. Para cada despesa, só seguem os orçamentos disponíveis no mês respetivo. Para rever uma liquidação existente, seleciona a receita e todas as despesas já associadas.

Na página [Classificar e liquidar](https://jokasman.github.io/carteira/classificacao/), o remetente carrega o pedido JSON da Carteira e recebe uma ligação curta. A outra pessoa abre essa ligação, atribui orçamentos e/ou liquida receitas sem carregar nem descarregar ficheiros. Também pode dividir uma despesa em duas partes, dar um nome e valor a cada uma e atribuir-lhes orçamentos opcionais. A soma das partes tem de igualar exatamente o valor original. Se ambas ficarem sem orçamento, a despesa dividida pode ser liquidada pelo total; se alguma parte tiver orçamento, a linha sai da lista de liquidações. Quando termina, avisa o remetente. Este volta ao mesmo link, descarrega o JSON de resposta e, na Carteira, toca em **Resposta**, escolhe o ficheiro, revê as decisões e toca em **Aplicar decisões**.

### Privacidade

O pedido contém somente os movimentos selecionados e os campos necessários à decisão. Não inclui a base de dados, contas ou notas. O pedido e a resposta ficam no Cloudflare D1; o link expira após 30 dias e os dados expirados são limpos em chamadas seguintes à API. O link contém apenas um token aleatório de acesso; qualquer pessoa com esse link consegue consultar o pedido e a resposta. O serviço usa um hash diário do endereço de rede para limitar a criação a 10 pedidos por dia e limpa hashes de dias anteriores em chamadas à API. A app guarda também o pedido na área privada por até 30 dias, exclui-o das cópias de segurança Android e valida novamente cada escolha antes de importar.

### Publicação

O GitHub Pages publica a pasta `docs` da branch `main`. A página usa a API do Worker Cloudflare para o armazenamento temporário; a configuração e as instruções estão em `cloudflare/classification-api/`.

As ligações de classificação antigas, criadas antes do armazenamento temporário, continuam a abrir a página de compatibilidade `docs/classificacao/legacy.html`.

## Abrir e executar

Abra esta pasta no Android Studio, aguarde a sincronização do Gradle e execute a configuração `app` num dispositivo/emulador Android 8.0 ou posterior. O projeto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Compose e Room.

## APK e instalação

O `applicationId` atual é `pt.jokasman.carteira`. Mantém este identificador para que o Android reconheça novas versões como atualizações da mesma app. A instalação antiga com `pt.carteira.app` continua separada e os dados locais não migram automaticamente.

O workflow **Build Android APK** compila quando um push para `main` altera a app ou a configuração Gradle, assina com a chave guardada em GitHub Secrets e publica o artefacto `Carteira-APK` quando o build tem sucesso. Se os secrets não estiverem disponíveis, o workflow não publica o APK. Nunca guardar a chave ou a palavra-passe no repositório. Os APKs não devem ser adicionados ao Git.

## Estado e decisões

Para o estado atual, os resultados de build e as instruções de continuidade, consulta [docs/estado-atual.md](docs/estado-atual.md). A importação bancária atual continua a ser por CSV; a integração Enable Banking/Firebase foi abandonada.

Os movimentos e as contas continuam no dispositivo. A classificação envia apenas os movimentos selecionados para armazenamento temporário na Cloudflare; não dá acesso à base de dados local nem às contas bancárias.
