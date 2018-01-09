/**
 * Deep increment.
 */
function main(params) {
    if (params.params) {
        params.params = main(params.params)
        return params
    }
    return { n: params.n + 1 }
}
